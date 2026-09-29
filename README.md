## Bloodygram

Unofficial Android client for Telegram, a fork of the [official Telegram for Android](https://github.com/DrKLO/Telegram).
Not affiliated with Telegram. Black and red.

### What it adds

- **Deleted messages stay** in the chat with a "deleted" mark, plus a **Trash** screen with every kept message and search
- **Edit history** of messages
- **View-once and timed media stay** in the chat (under a spoiler), with a "burn for sender" option
- **Streaks** — a fire with the number of days in a row you both wrote, colors by level, evening reminder
- **Ghost mode** — no read receipts, "online", "typing" or story views
- **AI in chats** — summarize a chat, reply ideas, explain a message or a voice message. Bring your own key:
  OpenRouter, Google Gemini and Groq have free keys; DeepSeek and Claude are paid
- **Voice transcription** on the phone, without Premium (Android 13+)
- Hidden chats behind a PIN, timed "delete for everyone", per-account themes, a year-in-review screen
- Chat background effects (snow, ash, topographic map), smooth typing and cursor, spring animations

### Plugins

A plugin is one JavaScript file. Install it from Bloodygram settings → Plugins, or long press a `.js` file
in any chat → "Install plugin". Plugins run in a sandbox: no Java or Android classes, no files, no network,
and every call is cut off after 0.3 s.

```js
// @name My plugin
// @description What it does
// @version 1.0
// @author you

// "/shout hi" in any chat sends "HI!!!"; return nothing (or "") to send nothing
bloody.command('shout', 'CAPS', function (args, chatId) {
  return args.toUpperCase() + '!!!';
});

// change any outgoing text; return undefined to leave it as is
bloody.onSend(function (text, chatId) {
  return text.replace(/:fire:/g, '🔥');
});

// react to new messages: msg = {text, chatId, fromId, id, out}
bloody.onMessage(function (msg) {
  if (!msg.out && /urgent/i.test(msg.text)) bloody.toast('Urgent message!');
});

bloody.storage.set('count', '1');           // per-plugin strings
var count = bloody.storage.get('count', '0');
bloody.log('loaded, ' + bloody.version);
```

### Download

APKs are on the [Releases](../../releases) page. The app checks this page for updates itself.

### Building

- Android Studio / JDK 21, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1
- `git -c core.longpaths=true submodule update --init --recursive --depth=1`
- Your own `api_id` / `api_hash` from [my.telegram.org](https://my.telegram.org) in `local.properties`:
  ```
  BLOODY_APP_ID=...
  BLOODY_APP_HASH=...
  ```
- `./gradlew :TMessagesProj_App:assembleAfatDebug`

Release builds are signed with the key from `local.properties` (`BLOODY_KEYSTORE`, `BLOODY_STORE_PASSWORD`,
`BLOODY_KEY_ALIAS`, `BLOODY_KEY_PASSWORD`); pushing a `v*` tag builds and publishes one via GitHub Actions.

Bloodygram code lives in `TMessagesProj/src/main/java/com/bloodygram/`; hooks in Telegram's files are marked `// Bloodygram`.

### License

GPL-2.0, like the upstream Telegram app.
