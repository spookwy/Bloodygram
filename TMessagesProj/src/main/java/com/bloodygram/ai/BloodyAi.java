package com.bloodygram.ai;

import android.text.TextUtils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

/** Claude API calls for the chat AI features (summary, reply ideas, explanations). The user brings their own API key. */
public class BloodyAi {

    public static final String DEFAULT_MODEL = "claude-opus-5";
    public static final String[] MODELS = {"claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"};

    private static final String SYSTEM = "You are an assistant built into Bloodygram, a Telegram client. "
            + "Answer in the language the conversation is written in (Russian if unsure). "
            + "Be concise and plain, no preamble, no markdown headings.";

    private static AnthropicClient client;
    private static String clientKey;

    public static boolean hasKey() {
        BloodyConfig.load();
        return !TextUtils.isEmpty(BloodyConfig.aiApiKey);
    }

    private static synchronized AnthropicClient client(String key) {
        if (client == null || !key.equals(clientKey)) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build();
            clientKey = key;
        }
        return client;
    }

    /**
     * One request, delivered on the UI thread: (text, null) or (null, error message for the user).
     * {@code effort} is kept low for quick UI answers; summaries use medium.
     */
    public static void ask(String task, String content, BetaOutputConfig.Effort effort, Utilities.Callback2<String, String> done) {
        BloodyConfig.load();
        String key = BloodyConfig.aiApiKey;
        if (TextUtils.isEmpty(key)) {
            done.run(null, BloodyStrings.get(R.string.BloodyAiNoKey));
            return;
        }
        String model = TextUtils.isEmpty(BloodyConfig.aiModel) ? DEFAULT_MODEL : BloodyConfig.aiModel;
        Utilities.globalQueue.postRunnable(() -> {
            String text = null, error = null;
            try {
                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(16000L)
                        .system(SYSTEM + "\n\n" + task)
                        .addUserMessage(content)
                        .outputConfig(BetaOutputConfig.builder().effort(effort).build());
                if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable")) {
                    // if a safety classifier declines, the server retries on a fallback model
                    params.addBeta(AnthropicBeta.of("server-side-fallback-2026-07-01"));
                    params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
                }
                BetaMessage message = client(key).beta().messages().create(params.build());
                if (message.stopReason().isPresent() && message.stopReason().get().equals(BetaStopReason.REFUSAL)) {
                    error = BloodyStrings.get(R.string.BloodyAiRefused);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (BetaContentBlock block : message.content()) {
                        block.text().ifPresent(t -> sb.append(t.text()));
                    }
                    text = sb.toString().trim();
                    if (text.isEmpty()) {
                        error = BloodyStrings.get(R.string.BloodyAiEmpty);
                    }
                }
            } catch (UnauthorizedException | PermissionDeniedException e) {
                error = BloodyStrings.get(R.string.BloodyAiBadKey);
            } catch (RateLimitException e) {
                error = BloodyStrings.get(R.string.BloodyAiRateLimit);
            } catch (AnthropicServiceException e) {
                FileLog.e(e);
                error = BloodyStrings.format(R.string.BloodyAiError, String.valueOf(e.statusCode()));
            } catch (Exception e) {
                FileLog.e(e);
                error = BloodyStrings.get(R.string.BloodyAiNetwork);
            }
            String finalText = text, finalError = error;
            AndroidUtilities.runOnUIThread(() -> done.run(finalText, finalError));
        });
    }
}
