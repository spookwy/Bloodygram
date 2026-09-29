package com.bloodygram.ai;

import android.text.TextUtils;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * AI providers. Claude goes through the Anthropic SDK ({@link BloodyAi}); the rest speak the OpenAI-compatible
 * chat/completions format, so one small HTTP client covers them. Each provider keeps its own key and model.
 */
public class BloodyAiProvider {

    public final String id;
    public final String name;
    /** chat/completions endpoint, null for Claude (SDK) */
    public final String url;
    /** where the user gets a key */
    public final String keySite;
    public final boolean free;
    public final String[] models;

    private BloodyAiProvider(String id, String name, String url, String keySite, boolean free, String... models) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.keySite = keySite;
        this.free = free;
        this.models = models;
    }

    public static final BloodyAiProvider CLAUDE = new BloodyAiProvider("claude", "Claude (Anthropic)", null, "console.anthropic.com", false, BloodyAi.MODELS);

    public static final BloodyAiProvider[] ALL = {
            new BloodyAiProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter.ai/keys", true,
                    "deepseek/deepseek-chat-v3-0324:free", "deepseek/deepseek-r1:free", "meta-llama/llama-3.3-70b-instruct:free", "qwen/qwen3-235b-a22b:free"),
            new BloodyAiProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "aistudio.google.com/apikey", true,
                    "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"),
            new BloodyAiProvider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "console.groq.com/keys", true,
                    "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b"),
            new BloodyAiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "platform.deepseek.com", false,
                    "deepseek-chat", "deepseek-reasoner"),
            CLAUDE,
    };

    public static BloodyAiProvider current() {
        BloodyConfig.load();
        String id = BloodyConfig.prefs().getString("aiProvider", null);
        if (id == null) {
            // existing Claude users keep Claude; everyone else starts on a free provider
            id = TextUtils.isEmpty(BloodyConfig.aiApiKey) ? ALL[0].id : CLAUDE.id;
        }
        for (BloodyAiProvider p : ALL) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return ALL[0];
    }

    public static void select(BloodyAiProvider provider) {
        BloodyConfig.putString("aiProvider", provider.id);
    }

    public String key() {
        BloodyConfig.load();
        return this == CLAUDE ? BloodyConfig.aiApiKey : BloodyConfig.prefs().getString("aiKey_" + id, "");
    }

    public void setKey(String key) {
        if (this == CLAUDE) {
            BloodyConfig.putString("aiApiKey", BloodyConfig.aiApiKey = key);
        } else {
            BloodyConfig.putString("aiKey_" + id, key);
        }
    }

    public String model() {
        BloodyConfig.load();
        String model = this == CLAUDE ? BloodyConfig.aiModel : BloodyConfig.prefs().getString("aiModel_" + id, null);
        return TextUtils.isEmpty(model) ? models[0] : model;
    }

    public void setModel(String model) {
        if (this == CLAUDE) {
            BloodyConfig.putString("aiModel", BloodyConfig.aiModel = model);
        } else {
            BloodyConfig.putString("aiModel_" + id, model);
        }
    }

    public String label() {
        return name + " · " + BloodyStrings.get(free ? R.string.BloodyAiFree : R.string.BloodyAiPaid);
    }

    /**
     * Blocking request; if the model is gone (404) picks a live one from /models, remembers it and retries once.
     * @return {text, null} or {null, error for the user}
     */
    String[] ask(String key, String system, String content) {
        String model = model();
        String[] result = complete(key, model, system, content);
        if (result.length > 2 && "404".equals(result[2])) {
            String replacement = pickModel(fetchModels(key));
            if (replacement != null && !replacement.equals(model)) {
                setModel(replacement);
                result = complete(key, replacement, system, content);
            }
        }
        return result;
    }

    /** A sensible default among the live models: a stable "flash" for Gemini, DeepSeek on OpenRouter, the 70B Llama on Groq. */
    private String pickModel(java.util.List<String> models) {
        if (models.isEmpty()) {
            return null;
        }
        String preferred = "gemini".equals(id) ? "flash" : "openrouter".equals(id) ? "deepseek" : "groq".equals(id) ? "70b" : "";
        String best = null;
        for (String m : models) { // lists are sorted, so the last match is usually the newest
            if (m.contains(preferred) && !m.contains("lite") && !m.contains("preview") && !m.contains("exp")) {
                best = m;
            }
        }
        if (best == null) {
            for (String m : models) {
                if (m.contains(preferred)) {
                    best = m;
                }
            }
        }
        return best != null ? best : models.get(0);
    }

    /** One OpenAI-compatible request. @return {text, null} or {null, error for the user[, http code]} */
    String[] complete(String key, String model, String system, String content) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 16000); // thinking models (Gemini 2.5+, R1) spend part of it before answering
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("X-Title", "Bloodygram"); // OpenRouter app attribution, ignored elsewhere
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                return new String[]{null, BloodyStrings.get(R.string.BloodyAiBadKey)};
            }
            if (code == 429) {
                return new String[]{null, BloodyStrings.get(R.string.BloodyAiRateLimit)};
            }
            if (code / 100 != 2) {
                String errorBody = read(connection.getErrorStream());
                FileLog.e("AI " + id + " HTTP " + code + ": " + errorBody);
                String detail = errorMessage(errorBody);
                return new String[]{null, BloodyStrings.format(R.string.BloodyAiError, code + (detail.isEmpty() ? "" : ": " + detail)), String.valueOf(code)};
            }
            JSONObject response = new JSONObject(read(connection.getInputStream()));
            String text = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            text = text.replaceAll("(?s)<think>.*?</think>", "").trim(); // reasoning models put their thinking inline
            if (text.isEmpty()) {
                return new String[]{null, BloodyStrings.get(R.string.BloodyAiEmpty)};
            }
            return new String[]{text, null};
        } catch (Exception e) {
            FileLog.e(e);
            return new String[]{null, BloodyStrings.get(R.string.BloodyAiNetwork)};
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** error.message from an OpenAI-style error body (Gemini wraps it in an array), shortened for a bulletin. */
    private static String errorMessage(String body) {
        try {
            String trimmed = body.trim();
            JSONObject root = trimmed.startsWith("[") ? new JSONArray(trimmed).getJSONObject(0) : new JSONObject(trimmed);
            String message = root.optJSONObject("error") != null ? root.getJSONObject("error").optString("message", "") : root.optString("message", "");
            return message.length() > 160 ? message.substring(0, 160) + "…" : message;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Models this key can use, from the provider's OpenAI-compatible /models list (names change often, so the
     * picker asks instead of trusting the built-in list). OpenRouter is narrowed to its free models.
     */
    public java.util.ArrayList<String> fetchModels(String key) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.replace("chat/completions", "models")).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            if (connection.getResponseCode() / 100 != 2) {
                FileLog.e("AI " + id + " models HTTP " + connection.getResponseCode() + ": " + read(connection.getErrorStream()));
                return result;
            }
            JSONArray data = new JSONObject(read(connection.getInputStream())).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                String model = data.getJSONObject(i).optString("id", "");
                if (model.startsWith("models/")) {
                    model = model.substring("models/".length()); // Gemini lists "models/gemini-..."
                }
                if (model.isEmpty() || "openrouter".equals(id) && !model.endsWith(":free")
                        || "gemini".equals(id) && (!model.startsWith("gemini") || model.contains("embedding") || model.contains("tts") || model.contains("image"))) {
                    continue;
                }
                result.add(model);
            }
            java.util.Collections.sort(result);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
