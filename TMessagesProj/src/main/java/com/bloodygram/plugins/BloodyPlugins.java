package com.bloodygram.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.Toast;

import com.bloodygram.BloodyConfig;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript plugins (Rhino, interpreted). A plugin is one .js file with a "// @name ..." header. It runs in a sandbox:
 * no Java classes (ClassShutter denies everything), no files or network, a time budget per call. It only sees the
 * `bloody` object:
 *   bloody.onSend(fn(text, chatId) -> string|undefined)   change your outgoing text
 *   bloody.onMessage(fn(msg))                              msg = {text, chatId, fromId, id, out}
 *   bloody.command(name, description, fn(args, chatId) -> string|undefined)   "/name args" typed in a chat;
 *                                                          the returned text is sent instead, nothing if undefined/""
 *   bloody.toast(text), bloody.log(text)
 *   bloody.storage.get(key[, def]), bloody.storage.set(key, value)   per-plugin strings
 * Everything runs on the UI thread.
 */
public class BloodyPlugins implements NotificationCenter.NotificationCenterDelegate {

    public static final int MAX_SIZE = 256 * 1024;
    private static final long TIMEOUT_MS = 300;
    private static final long LOAD_TIMEOUT_MS = 1500;

    public static class Command {
        public final String name, description;
        final Function fn;

        Command(String name, String description, Function fn) {
            this.name = name;
            this.description = description;
            this.fn = fn;
        }
    }

    public static class Plugin {
        public final String id;
        public String name, description = "", version = "", author = "";
        public String error;
        String source;
        Scriptable scope;
        final ArrayList<Function> onSend = new ArrayList<>();
        final ArrayList<Function> onMessage = new ArrayList<>();
        public final LinkedHashMap<String, Command> commands = new LinkedHashMap<>();

        Plugin(String id) {
            this.id = id;
            this.name = id;
        }

        public boolean isEnabled() {
            return BloodyConfig.prefs().getBoolean("plugin_on_" + id, true);
        }
    }

    private static final LinkedHashMap<String, Plugin> plugins = new LinkedHashMap<>();
    private static boolean loaded;
    private static final boolean[] observing = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected org.mozilla.javascript.Context makeContext() {
            org.mozilla.javascript.Context cx = super.makeContext();
            cx.setOptimizationLevel(-1); // interpreter: no bytecode generation on Android, and instruction counting works
            cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(5000);
            cx.setClassShutter(className -> false); // no Java access from scripts at all
            return cx;
        }

        @Override
        protected void observeInstructionCount(org.mozilla.javascript.Context cx, int instructionCount) {
            Object deadline = cx.getThreadLocal("deadline");
            if (deadline instanceof Long && System.currentTimeMillis() > (Long) deadline) {
                throw new Error("plugin took too long");
            }
        }
    };

    // region registry

    private static File dir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "plugins");
        dir.mkdirs();
        return dir;
    }

    public static synchronized ArrayList<Plugin> list() {
        ensureLoaded();
        return new ArrayList<>(plugins.values());
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File[] files = dir().listFiles((d, name) -> name.endsWith(".js"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            try {
                String id = file.getName().substring(0, file.getName().length() - 3);
                Plugin plugin = parse(id, read(new FileInputStream(file)));
                plugins.put(id, plugin);
                if (plugin.isEnabled()) {
                    load(plugin);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /** Header fields without running anything. */
    public static Plugin parse(String id, String source) {
        Plugin plugin = new Plugin(id);
        plugin.source = source;
        Matcher m = Pattern.compile("^\\s*//\\s*@(\\w+)\\s+(.+?)\\s*$", Pattern.MULTILINE).matcher(source);
        while (m.find()) {
            String value = m.group(2);
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "name": plugin.name = value; break;
                case "description": plugin.description = value; break;
                case "version": plugin.version = value; break;
                case "author": plugin.author = value; break;
            }
        }
        return plugin;
    }

    /** Id from the file name or the @name, safe for a file name. */
    public static String makeId(String fileName, Plugin parsed) {
        String base = fileName != null && fileName.endsWith(".js") ? fileName.substring(0, fileName.length() - 3) : parsed.name;
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "plugin" : id;
    }

    public static synchronized void install(String fileName, String source) throws Exception {
        ensureLoaded();
        Plugin parsed = parse("tmp", source);
        String id = makeId(fileName, parsed);
        try (FileOutputStream out = new FileOutputStream(new File(dir(), id + ".js"))) {
            out.write(source.getBytes(StandardCharsets.UTF_8));
        }
        Plugin plugin = parse(id, source);
        plugins.put(id, plugin);
        BloodyConfig.putBoolean("plugin_on_" + id, true);
        load(plugin);
    }

    public static synchronized void remove(Plugin plugin) {
        plugins.remove(plugin.id);
        new File(dir(), plugin.id + ".js").delete();
        BloodyConfig.prefs().edit().remove("plugin_on_" + plugin.id).apply();
        ApplicationLoader.applicationContext.getSharedPreferences("plugin_" + plugin.id, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static synchronized void setEnabled(Plugin plugin, boolean enabled) {
        BloodyConfig.putBoolean("plugin_on_" + plugin.id, enabled);
        if (enabled) {
            load(plugin);
        } else {
            unload(plugin);
        }
    }

    public static String read(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_SIZE) {
                    throw new Exception("too big");
                }
            }
            return out.toString("UTF-8");
        }
    }

    // endregion

    // region running

    private static void unload(Plugin plugin) {
        plugin.scope = null;
        plugin.onSend.clear();
        plugin.onMessage.clear();
        plugin.commands.clear();
    }

    private static void load(Plugin plugin) {
        unload(plugin);
        plugin.error = null;
        org.mozilla.javascript.Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal("deadline", System.currentTimeMillis() + LOAD_TIMEOUT_MS);
            ScriptableObject scope = cx.initSafeStandardObjects();
            scope.put("bloody", scope, api(cx, scope, plugin));
            plugin.scope = scope;
            cx.evaluateString(scope, plugin.source, plugin.id + ".js", 1, null);
        } catch (Throwable t) {
            plugin.error = t.getMessage();
            unload(plugin);
            FileLog.e(t);
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    private interface Body {
        Object run(Object[] args);
    }

    private static void fn(ScriptableObject target, String name, Body body) {
        target.put(name, target, new BaseFunction() {
            @Override
            public Object call(org.mozilla.javascript.Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object result = body.run(args);
                return result == null ? Undefined.instance : result;
            }
        });
    }

    private static String str(Object[] args, int i) {
        return i < args.length && args[i] != null && !Undefined.isUndefined(args[i]) ? org.mozilla.javascript.Context.toString(args[i]) : null;
    }

    private static ScriptableObject api(org.mozilla.javascript.Context cx, ScriptableObject scope, Plugin plugin) {
        ScriptableObject api = (ScriptableObject) cx.newObject(scope);
        fn(api, "onSend", args -> {
            if (args.length > 0 && args[0] instanceof Function) {
                plugin.onSend.add((Function) args[0]);
            }
            return null;
        });
        fn(api, "onMessage", args -> {
            if (args.length > 0 && args[0] instanceof Function) {
                plugin.onMessage.add((Function) args[0]);
            }
            return null;
        });
        fn(api, "command", args -> {
            String name = str(args, 0);
            Object handler = args.length > 2 ? args[2] : null;
            if (name != null && handler instanceof Function) {
                name = name.replaceFirst("^/", "").toLowerCase(Locale.ROOT);
                plugin.commands.put(name, new Command(name, TextUtils.isEmpty(str(args, 1)) ? "" : str(args, 1), (Function) handler));
            }
            return null;
        });
        fn(api, "toast", args -> {
            String text = str(args, 0);
            if (text != null) {
                AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show());
            }
            return null;
        });
        fn(api, "log", args -> {
            FileLog.d("plugin " + plugin.id + ": " + str(args, 0));
            return null;
        });
        ScriptableObject storage = (ScriptableObject) cx.newObject(scope);
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("plugin_" + plugin.id, Context.MODE_PRIVATE);
        fn(storage, "get", args -> {
            String key = str(args, 0);
            String value = key == null ? null : prefs.getString(key, null);
            return value != null ? value : args.length > 1 ? args[1] : null;
        });
        fn(storage, "set", args -> {
            String key = str(args, 0);
            if (key != null) {
                String value = str(args, 1);
                if (value == null) {
                    prefs.edit().remove(key).apply();
                } else if (value.length() <= 16 * 1024) {
                    prefs.edit().putString(key, value).apply();
                }
            }
            return null;
        });
        api.put("storage", api, storage);
        api.put("version", api, com.bloodygram.update.BloodyUpdater.versionLabel());
        return api;
    }

    private static Object call(Plugin plugin, Function fn, Object... args) {
        if (plugin.scope == null) {
            return null;
        }
        org.mozilla.javascript.Context cx = FACTORY.enterContext();
        try {
            cx.putThreadLocal("deadline", System.currentTimeMillis() + TIMEOUT_MS);
            Object result = fn.call(cx, plugin.scope, plugin.scope, args);
            return result == null || Undefined.isUndefined(result) ? null : result;
        } catch (Throwable t) {
            plugin.error = t.getMessage();
            FileLog.e(t);
            return null;
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    /**
     * Hook before a text message is sent from the input.
     * @return the text to send (the same object if untouched, keeping formatting), or null to send nothing
     */
    public static CharSequence onSend(int account, long dialogId, CharSequence text) {
        if (text == null || text.length() == 0) {
            return text;
        }
        ArrayList<Plugin> active = active();
        if (active.isEmpty()) {
            return text;
        }
        String original = text.toString();
        String s = original;
        if (s.startsWith("/")) {
            String[] parts = s.substring(1).split("\\s+", 2);
            String name = parts[0].toLowerCase(Locale.ROOT);
            for (Plugin plugin : active) {
                Command command = plugin.commands.get(name);
                if (command != null) {
                    Object result = call(plugin, command.fn, parts.length > 1 ? parts[1] : "", (double) dialogId);
                    if (result == null) {
                        return null;
                    }
                    s = org.mozilla.javascript.Context.toString(result);
                    if (s.isEmpty()) {
                        return null;
                    }
                    break;
                }
            }
        }
        for (Plugin plugin : active) {
            for (Function fn : new ArrayList<>(plugin.onSend)) {
                Object result = call(plugin, fn, s, (double) dialogId);
                if (result != null) {
                    s = org.mozilla.javascript.Context.toString(result);
                }
            }
        }
        if (s.equals(original)) {
            return text;
        }
        return s.isEmpty() ? null : s;
    }

    private static ArrayList<Plugin> active() {
        ArrayList<Plugin> result = new ArrayList<>();
        for (Plugin plugin : list()) {
            if (plugin.scope != null && plugin.isEnabled()) {
                result.add(plugin);
            }
        }
        return result;
    }

    // endregion

    // region incoming messages

    public static void start(int account) {
        if (!observing[account]) {
            observing[account] = true;
            NotificationCenter.getInstance(account).addObserver(new BloodyPlugins(), NotificationCenter.didReceiveNewMessages);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || args.length < 2 || args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return;
        }
        ArrayList<Plugin> active = null;
        for (Plugin plugin : list()) {
            if (plugin.scope != null && plugin.isEnabled() && !plugin.onMessage.isEmpty()) {
                if (active == null) {
                    active = new ArrayList<>();
                }
                active.add(plugin);
            }
        }
        if (active == null) {
            return;
        }
        @SuppressWarnings("unchecked") ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        for (MessageObject message : messages) {
            if (message == null || message.messageOwner == null) {
                continue;
            }
            for (Plugin plugin : active) {
                org.mozilla.javascript.Context cx = FACTORY.enterContext();
                Scriptable msg;
                try {
                    msg = cx.newObject(plugin.scope);
                    msg.put("text", msg, message.messageOwner.message == null ? "" : message.messageOwner.message);
                    msg.put("chatId", msg, (double) message.getDialogId());
                    msg.put("fromId", msg, (double) message.getSenderId());
                    msg.put("id", msg, (double) message.getId());
                    msg.put("out", msg, message.isOut());
                } finally {
                    org.mozilla.javascript.Context.exit();
                }
                for (Function fn : new ArrayList<>(plugin.onMessage)) {
                    call(plugin, fn, msg);
                }
            }
        }
    }

    // endregion
}
