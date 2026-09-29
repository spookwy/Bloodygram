# Bloodygram (бывший Epicgram)

Кастомный Android-клиент Telegram. Фишки: сохранение удалённых сообщений и истории правок (как AyuGram), огоньки-стрики в чатах (перенос плагина «Огонёк»), чёрно-красный дизайн.

Пользователь общается по-русски, неформально; хочет, чтобы Claude делал всё сам (сборка, установка, проверка на эмуляторе), а не выдавал команды.

## Как работаем (цикл разработки)

- **Запуск на ПК:** `powershell -ExecutionPolicy Bypass -File Tools\bloodygram\run.ps1` — собирает x86_64-debug, поднимает эмулятор AVD `epic` (если не запущен, `-gpu host`), ставит (`adb install -r -t`) и запускает. В эмуляторе пользователь **уже залогинен в свой аккаунт** — можно проверять всё вживую (чаты, огоньки, профиль).
- `run.ps1 -Phone` — установочный APK для телефона → `Desktop\Bloodygram-debug.apk`.
- `gradlew.bat` в апстриме нет — добавлен свой (стандартный wrapper, CRLF).
- adb: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe` (не в PATH). Скриншоты/логи: `adb exec-out screencap -p > file.png`, `adb logcat -d`, `adb exec-out uiautomator dump /dev/tty`. В Git Bash для `adb shell` с путями `/sdcard/...` нужен `MSYS_NO_PATHCONV=1` (но **не** для `gradlew`). Тап: `adb shell input tap X Y` (экран 1080×2400; скриншот в Read показывается 900×2000 → координаты ×1.2).
- Цвет пикселя со скриншота: `node <scratchpad>/px.js file.png x,y` (мини-декодер PNG, писали в сессии 28.09.2026; при необходимости написать заново — zlib + фильтры PNG).
- Эмулятор с `-gpu swiftshader_indirect` падает (exit 139) — только `-gpu host`.
- Ненадёжные ограничения окружения: удаление содержимого рабочей папки через `rm -rf` блокируется защитой Claude Code. Многострочные heredoc с кавычками в Bash-tool иногда ломаются — скрипты писать файлом через Write.

## База

- Форк **официального Telegram for Android** — https://github.com/DrKLO/Telegram (стартовали с 12.10.5).
- exteraGram не используем: публичный репо заархивирован и застрял на 9.6.6 (2023).
- Лицензия **GPL-2.0** — исходники Bloodygram обязаны быть публичными.
- Требования Telegram к сторонним клиентам: свой `api_id`, не называться «Telegram», не использовать их логотип.

## Сборка

- Android Studio 2025.1.4+, **NDK 27.2.12479018**, Android SDK 36, JDK 17/21.
- Сабмодули: `git -c core.longpaths=true submodule update --init --recursive --depth=1` (на Windows без `core.longpaths` падает сабмодуль `TMessagesProj_Modules/media`).
- Ключи: `BLOODY_APP_ID` / `BLOODY_APP_HASH` в `local.properties` (в `.gitignore`) → `BuildConfig.BLOODY_APP_ID/BLOODY_APP_HASH` (задаются в `TMessagesProj/build.gradle`) → `BuildVars.APP_ID/APP_HASH`. Получить на https://my.telegram.org. Реальные ключи **не коммитить**.
- Подпись: `TMessagesProj/config/release.keystore` + пароли в `gradle.properties` — пока dummy из апстрима; для релиза заменить на свои.
- `google-services.json` во всех модулях — заглушки (placeholder-проект). Пуши через FCM не работают, пока не заведём свой Firebase-проект.
- Сборка debug для установки на телефон: `./gradlew :TMessagesProj_App:assembleAfatDebug`
  APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`, пакет `com.bloodygram.messenger.beta`.
- `applicationId` = `com.bloodygram.messenger` (`APP_PACKAGE` в `gradle.properties`, заглушки `google-services.json`, run.ps1) — сменили 28.09.2026 с `com.epicgram.messenger`. После смены в эмуляторе нужен новый вход; старое приложение `com.epicgram.messenger.beta` ещё установлено рядом.
- ⚠️ Флаг `-Pandroid.injected.build.abi=...` СЕЙЧАС СЛОМАН в этом окружении: ломает плагин google-services 4.3.15 (`Failed to apply plugin ... No such property: libraryVariants for class: java.lang.String`). Падает и на чистом коммите — это проблема окружения (Gradle 8.13/AGP), не наших правок. Пока собираем полный afat без флага (`.\gradlew.bat :TMessagesProj_App:assembleAfatDebug`), ~1 мин с кэшем native; готовый APK `TMessagesProj_App\build\outputs\apk\afat\debug\app.apk` (не testOnly) ставится `adb install -r`.
- (когда флаг работал) Быстрая проверка компиляции (только arm64): `-Pandroid.injected.build.abi=arm64-v8a`. **Такой APK помечен `testOnly=true`** и ставится только через `adb install -t`, файлом на телефон — «пакет недействителен». Лежит в `build/intermediates/apk/...`.
- Подпись debug сейчас — dummy `release.keystore` из апстрима (публичный ключ, Play Protect ругается). Перед раздачей людям — свой keystore, иначе потом придётся переустанавливать с потерей данных.
- Модули: `TMessagesProj` — весь код клиента (library); `TMessagesProj_App` — обёртка-приложение, которую собираем. Остальные `TMessagesProj_App*` (Huawei, HockeyApp, Standalone, Tests) не трогаем.

## Правила кода

- **Минимум правок в файлах апстрима.** Вся наша логика — в отдельном пакете `TMessagesProj/src/main/java/com/bloodygram/`. В файлах Telegram — только короткие хуки-вызовы в наш код.
- Каждый хук в апстрим-файле помечаем комментарием `// Bloodygram` (или блоком `// Bloodygram start` / `// Bloodygram end`), чтобы легко находить при мердже обновлений.
- `namespace` (`org.telegram.messenger`) не трогаем — меняем только `applicationId`, иначе переписывать тысячи импортов.
- Настройки — отдельный `SharedPreferences`, класс `com.bloodygram.BloodyConfig`. Экран — `com.bloodygram.ui.BloodySettingsActivity` (UniversalFragment), вход — пункт id `1000` в `SettingsActivity`.
- **Имена файлов данных остались старыми** (`epicgram_config`, `epicgram_<userId>.db`, `epicgram_edits_<userId>.db`, `epicgram_streaks_<userId>.db`, канал уведомлений `epicgram_keep_alive`) — чтобы после переименования не потерялись сохранённые удалённые сообщения/стрики. Не переименовывать без миграции.
- Строки — `res/values/strings_bloodygram.xml` (англ.) и `res/values/strings_bloodygram_ru.xml` (те же имена с суффиксом `_ru`), префикс `Bloody`. **Не в `values-ru/`**: app-модуль собирается с `localeFilters "zz"`, все локализованные ресурсы вырезаются. Читать **только через `com.bloodygram.BloodyStrings.get/format`** — берёт `_ru` по языку Telegram; `LocaleController.getString` их не видит. Склонение дней — `BloodyStrings.days(n)`.
- **Имя приложения:** облачные строки Telegram подменяют `AppName` на «Telegram», поэтому хук в `LocaleController.getStringV2` всегда отдаёт `BloodyStrings.APP_NAME`. Заголовок над списком чатов в `DialogsActivity` был картинкой-логотипом `telegram_logo_2` — заменён на `BloodyStrings.appTitle()` («Bloody» красным).
- Данные — свои SQLite-базы на аккаунт, в базу Telegram не пишем. Синглтоны по аккаунту пересоздаются при смене userId.
- **Переводы строк:** в репо `core.autocrlf=false` + `.gitattributes` (`* -text`), всё хранится в LF. Раньше рабочая копия была в CRLF (глобальный autocrlf=true) — 28.09.2026 нормализовали. Если после правки `git diff` показывает весь файл — проверь `git ls-files --eol` (`i/lf w/crlf`) и сними `\r`. `grep -c $'\r'` в Git Bash тут врёт — смотреть `od -c`. Ассеты не конвертировать.
- **Ассет темы** Telegram копирует в `files/` и обновляет копию только при смене **размера** файла — правка цвета той же длины не доходила до приложения. Хук `BloodyTheme.isStaleCopy` в `Theme.getAssetFile` перекопирует нашу тему после каждого обновления APK.
- Эмулятор для проверок: AVD `epic` (Android 35, x86_64, WHPX). Сборка под него: полный afat без abi-флага (флаг сейчас ломает google-services, см. раздел «Сборка») → `adb install -r ...\outputs\apk\afat\debug\app.apk`. Не экспортировать `MSYS_NO_PATHCONV` при запуске `gradlew` — ломает wrapper. Эмулятор относится к среднему классу производительности: Lite Mode выключает `FLAG_CHAT_BACKGROUND`.
- Python в системе нет (только заглушка Store) — для скриптов использовать bash/node.
- Git: локальная идентичность репо `xdlolpicd2 <xdlolpicd2@gmail.com>` (глобальной нет).

## Модули

- `com.bloodygram.deleted.BloodyDeletedMessages` — перехват `updateDeleteMessages`/`updateDeleteChannelMessages` в `MessagesController.processUpdateArray` (`continue` вместо удаления), пометка «удалено» в `ChatMessageCell.measureTime`, перерисовка через `NotificationCenter.bloodyMessagesMarkedDeleted` → `ChatActivity.updateVisibleRows(condition)`.
- `com.bloodygram.history.BloodyEditHistory` — хук после `MessageObject.getDialogId(message)` в ветке edit-апдейтов; старая версия читается из `messages_v2` на `storageQueue` Telegram (до `putMessages` новой). Пункт меню `OPTION_EDIT_HISTORY = 10001` в обоих билдерах контекстного меню `ChatActivity`.
- `com.bloodygram.streaks.BloodyStreaks` — дни (флаги мои/собеседника) по личкам в `epicgram_streaks_<userId>.db` (`streak_days`, `streak_sync`, `streak_stats`, версия БД 3).
  - Скан (`startScan` → `loadLocalDays` → `walk`/`next`/`fetch`/`applyPage`): сначала дни «оба писали» из локальной БД Telegram (`messages_v2`, GROUP BY дню, как `_local_day_flags` плагина — берём только положительные дни, кэш с дырами); затем идём по дням назад, каждый неизвестный день — запрос `getHistory` с `offset_date = конец дня`, по `PROBES = 3` дня параллельно; день, не влезший в страницу, догружается по `offset_id` (`continueFrom`); `completeBelow` — история кончилась.
  - Очередь: до `MAX_PARALLEL = 3` чатов одновременно, выбирается чат, показанный последним (`wanted`: время из `getStreak()`/`requestSyncIfStale`, `front` = +1 ч). Все запросы идут через общий темп `REQUEST_INTERVAL = 500` мс: при ~3 запросах/с Telegram уже отвечает FLOOD_WAIT 10–25 с (короткие FLOOD_WAIT tgnet переотправляет сам, мы их не видим). Лимит 800 запросов на скан. Первичный подсчёт стрика 175 дней ≈ 1–1.5 мин — упирается в лимит сервера; повторные — мгновенно (дни кэшируются).
  - Прогресс: `getScanProgress(dialogId)` (дней проверено), `notifyProgress()` → только `NotificationCenter.bloodyStreaksUpdated` (без перестройки списка).
  - Инкремент по `didReceiveNewMessages`. Счётчик удалённых: `onRemoteDelete` (из `BloodyDeletedMessages.interceptDelete`) и `onOwnDelete` (хук в `MessagesController.deleteMessages`). Обновление UI — `notifyUi()` → `updateInterfaces(UPDATE_MASK_NAME)` + `bloodyStreaksUpdated`.
  - `getNameColor/colorName` — цвет имени по уровню огонька (`BloodyFire.nameColor(tier)` = средний цвет пламени, серый не красим), настройка `streakNameColor`.
- `com.bloodygram.streaks.BloodyStreakStats` — статистика для окна «Огонёк» (порт `StatsJob` плагина): листает историю от первого сообщения (`offset_id=mx+1, add_offset=-100, min_id=mx`), инициатива с окном 30 мин, прогресс сохраняется в `streak_stats` после каждой страницы.
- `com.bloodygram.streaks.BloodyStreakUi` — окно «Огонёк» (`showStats`, во время подсчёта — полоска `ProgressBar` + «проверено N дней»), строка профиля (`bindProfileRow`), тап по огоньку в шапке (`HeaderTouch`), пункт меню `MENU_ID = 10002`, хелперы `listSuffix/listName/listHash/headerTitle/profileName` с учётом настроек.
  - **Список чатов:** `DialogCell.DialogUpdateHelper.update()` сравнивает `lastDrawnBloodyFire` с `BloodyStreakUi.listHash()` (число, уровень, настройки) — при изменении огонька layout перестраивается (раньше в списке висел «0», а в шапке уже 6).
- `com.bloodygram.streaks.BloodyFire` — векторный огонёк (пути из SVG плагина «Огонёк», `C:\Users\SWAGA_PA3PEIIIEHA\Desktop\Projects\Fire Ayugram\ogonek.plugin` — эталон дизайна), уровни цвета: серый (0/под угрозой), оранжевый, 100+ красный, 200+ фиолетовый, 300+ синий, 500+ зелёный. Суффикс « 🔥N» после имени в `DialogCell.buildLayout` и в `ChatAvatarContainer.setTitle`.
- `com.bloodygram.ui.BloodyProfile` — строка «ID» в профиле (как AyuGram): `bloodyIdRow` в `ProfileActivity` после `usernameRow` (для людей) и в блоке инфо чатов/каналов (блок показывается и без описания, если включён ID); id в стиле Bot API (`-100…` для каналов/супергрупп, `-…` для обычных групп); тап копирует. Настройка `showPeerId`.
- `com.bloodygram.BloodyTheme` — имя ассета темы, `isStaleCopy`.
- `com.bloodygram.keepalive.BloodyKeepAliveService` — foreground-сервис (`remoteMessaging`) вместо FCM: хук в `ApplicationLoader.startPushService()`, включает `pushConnection`. Без него закрытое приложение не получает апдейты → удалённые не сохраняются.
- `com.bloodygram.ui.BloodyTypingEffects` — эффекты поля ввода (хук `attach(messageEditText)` в `ChatActivityEnterView.createMessageEditText`): набранные буквы (≤3 за раз, не вставка/черновик) проявляются и всплывают за 220 мс — `AppearSpan` (CharacterStyle: alpha + `baselineShift`), на время анимации у поля `LAYER_TYPE_SOFTWARE`, иначе TextView кэширует блоки текста в display list и span не перерисовывается. Стёртые буквы (≤16, не очистка всего поля) — снимок из `Layout` → частицы `Dust` в `rootView.getOverlay()`, разлёт вправо-вверх с волной слева направо. Композиция клавиатуры учитывается по общему префиксу старого/нового текста. Настройки `typingAnimation`, `eraseDust` (+ Lite Mode `FLAG_PARTICLES`).
- **Снег в чатах:** `SizeNotifierFrameLayout.checkSnowflake` + `SnowflakesEffect.onDraw` — рисуется при празднике Telegram или при `BloodyConfig.isChatSnowAllowed()` (настройка `chatSnow` + Lite Mode `FLAG_PARTICLES`, который есть во всех пресетах; праздничный снег Telegram требует `FLAG_CHAT_BACKGROUND` — только «высокий» пресет). Добавлен вызов и для градиентных обоев без паттерна.
- **Тема «Bloodygram»:** `assets/bloodygram.attheme` генерируется `node Tools/bloodygram/make_theme.js` из `night.attheme`. Исходящие пузыри — градиент `chat_outBubble` (красный) → `chat_outBubbleGradient/2/3` (тёмно-красный → почти чёрный), `chat_outBubbleGradientAnimated=1`: градиент привязан к экрану, вверху пузыри тёмные, внизу красные, «переливаются» при скролле. В `Theme.java` тема зарегистрирована как «Bloodygram» + алиас «Epicgram» (старое имя в сохранённых настройках); флаг `bloodyThemeDefaultApplied`. Кнопки голосовых в исходящих — чёрный кружок (`chat_outLoader`/`chat_outLoaderSelected`), значок play красным поверх.
- `com.bloodygram.ghost.BloodyGhost` — режим призрака, per-account (`BloodyConfig.isGhost(account)`). Хук в `ConnectionsManager.sendRequestInternal` — глотает `TL_account.updateStatus`/`setTyping`/`readHistory`/`readMessageContents`/сторис-вьюхи, если включено и включён соответствующий подпункт (`ghostRead/ghostOnline/ghostTyping/ghostStories`). «Прочитать» вручную в меню чата шлёт `readHistory` напрямую в обход перехвата.
- `com.bloodygram.chat.BloodySecretSaver` (было `BloodySecretSaver`/сохранение self-destruct) — исчезающие/одноразовые фото, видео, голосовые сохраняются в галерею (папка Bloodygram) сразу при получении, расшифровка `.enc` через `EncryptedFileInputStream`. Тумблер `saveSecretMedia`.
- `com.bloodygram.secret.BloodySecretKeeper` — одноразовые/таймерные медиа остаются в чате обычными (без блюра, не превращаются в «Expired photo»). Хук в конце `TLRPC.Message.TLdeserialize` (через него идёт каждое сообщение — и из сети, и из локальной БД): у входящих не-секретных сообщений с живым медиа обнуляется `media.ttl_seconds` (+ флаг `4`, + `message.ttl`) — всё в Telegram (блюр, SecretMediaViewer, таймер, `emptyMessagesMedia`) завязано на это поле. Копия медиа — в `epicgram_secret_media.db` (глобальная, ключ `from:peer:id:date`); если сервер позже пришлёт уже «сгоревшую» версию (photoEmpty/documentEmpty с ttl), она подменяется копией. Уже сгоревшие до установки фичи не восстановить. Тумблер `keepSecretMedia`. Подпись срока перед временем: «🔥 1 просмотр» (ttl `0x7FFFFFFF`), «🔥 10 секунд» (`LocaleController.formatTTLString`), после сжигания «🔥 сожжено» (`timeMark`, хук в `ChatMessageCell.measureTime`). Фото/видео приходят под штатным спойлером: хук в `MessageObject.hasMediaSpoilers()` (+ поле-кэш `bloodySecretSpoiler`, метод зовётся на каждый кадр) → `needsSpoiler`; раскрытие — штатное `isMediaSpoilersRevealed`, живёт только в памяти, поэтому при повторном входе в чат блюр возвращается. На эмуляторе пыль-частицы спойлера не рисуются вообще (и у обычных спойлеров тоже) — только блюр; это не наш баг. состояние (исходный ttl, burned) кэшируется в памяти по ключу, чтобы не лезть в БД на каждый layout. Пункт меню «Сжечь для отправителя» (`OPTION_BURN = 10008`) шлёт `readMessageContents` через `BloodyGhost.send` (проходит режим призрака) — у отправителя сгорает, у нас остаётся (ttl уже снят); перерисовка через `bloodyMessagesMarkedDeleted` (условие в `ChatActivity` расширено на `wasSecret`). Ключ в личке: `uid:uid:mid:date` (у входящих `from_id` нет, после фиксапа = `peer_id` = собеседник). Для ручной проверки подписи без настоящего одноразового фото: `run-as ... sqlite3 databases/epicgram_secret_media.db "insert into media values('<key>', x'00', 2147483647, 0)"` + force-stop. `BloodySecretSaver.isSelfDestructing` учитывает `BloodySecretKeeper.wasSecret`, иначе после снятия таймера сохранение в галерею перестало бы срабатывать. Секретные (E2E) чаты не трогаем.
- `com.bloodygram.chat.BloodyTranscriber` — расшифровка голосовых/видеосообщений в текст без Premium, на устройстве (Android 13+, `SpeechRecognizer`). Декодирует voice/round-video в PCM 16 кГц через `MediaCodec`, отдаёт в `SpeechRecognizer.createOnDeviceSpeechRecognizer` с `EXTRA_AUDIO_SOURCE`=pipe. **On-device распознавание не читает аудио-источник, если языковой пакет не скачан** — тогда `onError` триггерит `triggerModelDownload` и просит повторить через минуту (не молча падает и не уходит в `NetworkSpeechRecognizer`, который с этим pipe не работает вообще — see `ERROR_LANGUAGE_UNAVAILABLE`/`ERROR_LANGUAGE_NOT_SUPPORTED` в `onError`). Язык — настройка `transcribeLang` (пусто = язык телефона). `withFile` ждёt загрузку файла с таймаутом 30 с (иначе завис бы спиннер/утекал наблюдатель, если `fileLoaded`/`fileLoadFailed` не пришли).
- `com.bloodygram.chat.BloodyMessageMenu` / `com.bloodygram.ai.BloodyAiChat` — пункты меню сообщения («Расшифровать», «✨ Объяснить») и меню чата («✨ Пересказать чат», «✨ Варианты ответа»), плюс `com.bloodygram.ai.BloodyAi` — тонкий клиент Anthropic Java SDK (`claude-opus-5` по умолчанию, ключ юзера в настройках, `server-side-fallback` бета для opus/fable). Диалоги строятся через `fragment.getParentActivity()`, с null-чеком до и после асинхронного вызова (фрагмент мог уйти со сцены, пока ждали ответ/скачивание файла).
- `com.bloodygram.chat.BloodyAutoDelete` — «Удалить у всех через…» из меню отправки (1мин/5мин/1час/1день): следующее отправленное в этот чат сообщение планируется на авто-удаление (`deleteMessages(forAll=true)`), очередь переживает перезапуск (SharedPreferences + `AlarmManager`/`BroadcastReceiver` будят процесс, если приложение не запущено).
- `com.bloodygram.deleted.BloodyTrashActivity` — «Корзина»: все сохранённые удалённые сообщения из всех чатов (`BloodyDeletedMessages.loadTrash`: id из нашей БД → сами сообщения из `messages_v2` Telegram, для каналов `uid = -channel_id`, для лички/групп `is_channel = 0`), `DialogCell.setDialog(dialogId, messageObject, ...)`, поиск по тексту и названию чата, тап открывает чат на сообщении (`message_id`). Вход: ⋮ над списком чатов и настройки.
- `com.bloodygram.chat.BloodyRemind` — «Напомнить» в меню сообщения (30 мин/1 ч/3 ч/завтра 9:00): JSON-очередь в prefs `reminders`, `AlarmManager` → `BroadcastReceiver` → уведомление (канал `bloodygram_reminders`), тап открывает чат на сообщении (`userId`/`chatId` + `message_id`). `schedule()` при старте (будильники теряются после перезагрузки).
- `com.bloodygram.ai.BloodyAiProvider` — провайдеры ИИ: OpenRouter/Gemini/Groq (бесплатные ключи), DeepSeek, Claude (SDK). Не-Claude — OpenAI-совместимый `chat/completions` на `HttpURLConnection`. Названия моделей устаревают → список в настройках берётся живьём из `/models` провайдера, а на 404 `ask()` сам выбирает живую модель и повторяет. Ключ/модель на провайдера: prefs `aiKey_<id>`/`aiModel_<id>` (Claude — старые `aiApiKey`/`aiModel`). `BloodyAiDigest` — «✨ Дайджест каналов» (⋮ над списком: непрочитанное до 15 каналов через `getHistory`, не помечает прочитанным) и «✨ Перевести» в меню сообщения.
- `com.bloodygram.streaks.BloodyStreakTopActivity` — «Мои огоньки»: живые серии по убыванию, подзаголовок «сегодня погаснут: N». Заморозка (`streakFreeze`, выкл. по умолчанию, иначе расходится с плагином): один пропущенный день в 7 дней не рвёт серию (`canFreeze` в `recompute`, `isAlive`, `isAtRisk`, `walk` скана).
- `com.bloodygram.ai.BloodySmartReplies` — полоска над полем ввода: после входящего сообщения (или при входе в чат, если последнее входящее свежее <6 ч и с текстом) чип «✨ Предложить ответ»; по тапу — запрос к ИИ (до тапа запросов нет — бережём лимит бесплатных ключей) → 3 чипа-ответа + ✕, тап вставляет в поле. Прячется при наборе/отправке. Добавляется в `ChatActivity` рядом с `suggestEmojiPanel` (только `chatMode == 0`), сдвиг по Y — в `updatePagedownButtonsPosition` вместе с `sideControlsButtonsLayout`. Тумблер `smartReplies`, показывается только при ключе ИИ. «✨ Перевести чат» — в ⋮ чата (`BloodyAiChat.translateChat`, `MENU_TRANSLATE = 10013`).
- Статистика в окне «Огонёк» (`BloodyStreakStats`): кроме плагинных полей — средняя скорость ответа моя/собеседника (ответ = сообщение после сообщения другой стороны, паузы >12 ч не считаются) и самый активный час; колонки `rs_me, rs_th, rc_me, rc_th, pd, po, hours` (БД стриков v4, при апгрейде обход статистики начинается заново, `deleted` сохраняется).
- `com.bloodygram.plugins.BloodyPlugins` — плагины на JS (Rhino 1.7.15, интерпретатор `optimizationLevel -1`, ES6). Файлы `files/plugins/<id>.js`, заголовок `// @name/@description/@version/@author`, вкл/выкл — prefs `plugin_on_<id>`, хранилище плагина — prefs `plugin_<id>`. Песочница: `ClassShutter` запрещает все Java-классы, `initSafeStandardObjects` (без `Packages`/`java`), API только объект `bloody` из `BaseFunction` (без рефлексии): `onSend`, `onMessage`, `command`, `toast`, `log`, `storage`, `version`. Лимит времени через `observeInstructionCount` (300 мс на вызов, 1.5 с на загрузку) → `Error`. Хук отправки в `ChatActivityEnterView.sendMessageInternal` сразу после `getTextToUse()` (`typedMessage` → `message`; null = команда обработана, отправки нет; если текст не менялся — возвращается тот же объект, форматирование сохраняется). Входящие — `didReceiveNewMessages` (старт в `Bloody.onAccountReady`). UI: `BloodyPluginsActivity` (настройки Bloodygram → «Плагины»; встроенный пример «Bloody tools»: /shout /mock /roll, :fire:→🔥), установка из файла (SAF) и из чата (долгое нажатие на .js → `OPTION_INSTALL_PLUGIN = 10014`). R8: keep `org.mozilla.javascript.**`. Проверено 30.09: команды и onSend работают, `java`/`Packages` недоступны, бесконечный цикл обрывается, приложение живо. Для ручных тестов на эмуляторе: `run-as ... cp` файла в `files/plugins/` + force-stop (в `run-as "a; b"` после `;` команды идут НЕ от приложения).
- Иконки-квадраты Telegram (настройки, профиль, меню) перекрашены в кровавую палитру: `BloodyTheme.iconColor` (по оттенку → алый/угольный/тёмно-кровавый/винный/багровый/розово-красный; идемпотентна — enum-цвета проходят через `SettingCell` второй раз), хуки в `IconBackgroundColors` (конструктор enum) и `SettingsActivity.SettingCell.set`. Тумблер `bloodyIcons`, полностью — после перезапуска.
- Релизный workflow: список изменений = заголовки коммитов между прошлым и текущим тегом (через GitHub API compare, клон shallow) → описание релиза; пост в Telegram-канал ботом, если заданы секреты `TELEGRAM_BOT_TOKEN` и `TELEGRAM_CHANNEL` (APK не прикладывается — лимит Bot API 50 МБ, в посте ссылка на скачивание).
- Известная утечка бренда: окно доступа к контактам «Telegram needs access to your contacts» (облачная строка). Не чинили.
- Системная заставка Android 12+: `TMessagesProj_App/res/drawable/tg_splash_320.xml` перекрывает иконку Telegram (inset 48dp под круг 160dp), фон заставки `#000000` в `values-v31/styles.xml` и `values-night/styles.xml`.
- История правок (`BloodyEditHistory`) показывает пословный дифф между версиями (LCS по словам): удалённое красным зачёркнутым, добавленное зелёным.
- Иконка: исходники `Tools/bloodygram/art/*.png` → `node Tools/bloodygram/make_icons.js` → `drawable-nodpi/bloody_logo.png` (в приложении), `bloody_icon_fg.png` (адаптивная иконка, чёрный фон), `bloody_icon_legacy.png`. Логотипы Telegram перекрыты в app-модуле через `drawable-anydpi` (`ic_launcher_dr`, `logo_middle`) — anydpi главнее плотностных папок библиотеки. Надпись «Telegram» в шапке историй (`DialogStoriesCell.telegramLogoView`) → `BloodyTitleDrawable`.
- `com.bloodygram.ui.BloodyAccounts` — своя тема на аккаунт: применяется в `LaunchActivity.switchToAccount` после переключения (**не** применяется при холодном старте в уже выбранный аккаунт — известное ограничение, см. «Следующие задачи»).
- `com.bloodygram.stats.BloodyWrapped`/`BloodyWrappedUi` — «Bloodygram Wrapped», статистика года по локально закэшированным сообщениям (топ собеседники, топ эмодзи, самые активные часы и т.п.), экран из настроек.
- `com.bloodygram.ui.BloodyFx` — визуальные эффекты: искры при отправке (`onSend`, ударная волна + частицы к кнопке отправки), фон чата — снег/угли/искры/**топографическая карта** (`chatParticles`: `PARTICLES_SNOW/ASH/SPARKS/TOPO`). **Топокарта** — контурные линии (marching squares по value-noise полю, два блендированных октава + `Paint.Join/Cap.ROUND` и более толстый `stroke` для скруглённости) красным на чёрном; вместо монотонного дрейфа в сторону — орбита фиксированного радиуса в noise-пространстве (`ORBIT_MS`/`ORBIT_RADIUS`, по `cos`/`sin` от фазы), поэтому узор не «уезжает» с экрана, а крутится на месте. Реагирует на свайпы: `SizeNotifierFrameLayout.backgroundTranslationY` прокинут третьим параметром через `drawChatParticles(view, canvas, scrollOffset)` в `TopoMap.draw()`, подмешивается в сэмплирование noise (`SCROLL_FOLLOW`). Пересчитывается раз в ~90 мс в закэшированный Bitmap (не каждый кадр — иначе дорого), сам блит на канвас идёт каждый кадр. Полностью красит фон в чёрный (не оверлей поверх обоев, как снег/угли/искры).
- `com.bloodygram.ui.BloodyMotion` — пружинная интерполяция переходов/анимаций сообщений (`springAnimations`), тумблер блюра шапки/панелей (Telegram’s `FLAG_CHAT_BLUR`) и **«жидкое стекло»** (`FLAG_LIQUID_GLASS` — существующий, но не включённый по умолчанию флаг апстрима: ярче и прозрачнее тонировка блюра); включение liquid glass само включает блюр, если он был выключен.
- `com.bloodygram.ui.BloodyCaret` — плавно скользящий курсор ввода взамен штатного (тот у Telegram прыгает мгновенно). Рисуется поверх `super.onDraw()` в `ChatActivityEditTextCaption` (только поле ввода чата, не общий `EditTextBoldCursor`/`EditTextCaption` — сознательно не трогаем шаренные классы, чтобы не сломать другие текстовые поля в приложении). Штатный курсор гасится через `setAllowDrawCursor(false)`, но переключается только при реальной смене состояния (`bloodySmoothCursorApplied`) — `setAllowDrawCursor` сам вызывает `invalidate()` безусловно, дёргать его каждый кадр = бесконечный перерисовочный цикл. Позиция считается ровно как в `EditTextBoldCursor.updateCursorPosition()` (`layout.getPrimaryHorizontal(offset)` без вычета scrollX/padding по горизонтали, `getLineTop(line)`/`getLineTop(line+1)` по вертикали). Движение — пружина с критическим затуханием по X и по строке (`OMEGA = 14` рад/с, ~300 мс до остановки, подшаги по 4 мс), стартует с нулевой скорости. Экспоненциальное сглаживание (было раньше) не годилось: пик скорости на первом кадре = рывок. Важно: при выходе из покоя `lastFrame = now - 16` — иначе первый шаг интегрирует секунды простоя и курсор прыгает (это и была причина «всё ещё резкий»). Толщина `2.6dp`. Проверка плавности без видео: `scratchpad/caret.js` ищет синие пиксели курсора на серии `screencap`, снятой прямо на устройстве (`adb shell "input text X; screencap ...; screencap ..."`), — видно промежуточные позиции.
- Стиль кода — как в Telegram: Java, без лишних абстракций, `AndroidUtilities.dp()`, `Theme.getColor()`.

## Git и релизы

- `origin` = https://github.com/spookwy/Bloodygram (публичный **форк** DrKLO/Telegram — поэтому пуш из shallow-клона работает: база 12.10.5 уже есть в форке). Ветка по умолчанию `main`. gh залогинен как `spookwy`.
- Релиз: поднять `BLOODY_VERSION_NAME` в `gradle.properties` → коммит → тег `v<версия>` → `.github/workflows/release.yml` собирает `assembleAfatRelease` (~1 ч, весь натив с нуля) и публикует `Bloodygram-<версия>.apk` в Releases. `APP_VERSION_*` не трогать — это версия Telegram (уходит на сервер).
- Секреты репозитория: `BLOODY_APP_ID/HASH`, `BLOODY_KEYSTORE_BASE64`, `BLOODY_STORE_PASSWORD`, `BLOODY_KEY_ALIAS`, `BLOODY_KEY_PASSWORD`. CI пишет из них `local.properties`.
- Своя подпись только у **release** (пакет `com.bloodygram.messenger`, без `.beta`): ключ `Desktop\Projects\Bloodygram-keys\bloodygram-release.jks` (вне репо; пароль там же в README.txt и в `local.properties`). Debug (`.beta`) по-прежнему на тестовом ключе апстрима — чтобы не переустанавливать с потерей данных. Потерять ключ = пользователи не смогут обновиться.
- `android-actions/setup-android@v3` на раннере падает (пакет `tools` удалён) — в workflow `sdkmanager` зовётся напрямую из предустановленного SDK.
- Обновления в приложении: `com.bloodygram.update.BloodyUpdater` — GitHub API `releases/latest`, сравнение тега с `BuildConfig.BLOODY_VERSION`; авто-проверка только в release, раз в 12 ч (хук в `LaunchActivity.onResume`), ручная — кнопка внизу настроек Bloodygram.
- В `git stash` лежит старая запись `epic-wip` — резервная копия раннего состояния, давно устарела; можно удалить (`git stash drop`).
- ⚠️ Не править `shared_prefs/*.xml` на эмуляторе через `sed` с подстановкой (`&` в замене = совпадение → битый XML → приложение молча сбрасывает ВСЕ настройки, так 29.09 пропали PIN/скрытые чаты на эмуляторе). Править локальную копию (node) → `adb push` в `/data/local/tmp` (с `MSYS_NO_PATHCONV=1`) → `run-as cp`, приложение при этом остановлено.

## Обновление от апстрима

- `origin` → наш GitHub, `upstream` → DrKLO/Telegram.
- Обновление: `git fetch upstream` → `git merge <тег/коммит релиза>` → конфликты ищем по `// Bloodygram`.
- Клон изначально shallow; перед первым мерджем: `git fetch --unshallow upstream`.

---

# Следующие задачи

- Иконка сейчас — самолётик Telegram в кровавом круге (выбор пользователя). По правилам Telegram для сторонних клиентов их логотип использовать нельзя — перед широкой раздачей заменить на свой знак.
- Не сделано из списка идей 29.09: подсказки ответа прямо над клавиатурой (сейчас — через ⋮ «Варианты ответа»), автоперевод всего чата, расширенная статистика по чату, выбор иконки приложения (нужна вторая картинка), проход темой по остальным экранам, плагины.
- Проверить удалённые сообщения после повторного открытия чата (см. Этап 1).
- Per-account тема (`BloodyAccounts`) не применяется при холодном старте в уже выбранный аккаунт — только при явном переключении через `switchToAccount`. Мелкий баг, не чинили из-за риска зацепить порядок восстановления темы при старте.
- Paid Media (Stars) — пользователь просил показывать платные медиа бесплатно/заблюренными. **Отказано**: это обход платёжной системы Telegram (сервер просто не отдаёт файл без оплаты), риск бана аккаунта, вне правил — не делать.
- Обход упирался в неудобство ручного тестирования через adb (`input swipe X Y X Y <duration>` с одинаковыми координатами эмулятор/эта сборка Telegram трактует как «войти в мультивыбор», а не как long-press → контекстное меню сообщения). Если нужно тестить контекстное меню сообщения через adb — искать другой способ синтезировать long-press (например `sendevent` с ощутимым дрожанием координат, или через uiautomator).

Сделано 28.09.2026 (я): баг «0» в списке, ускорение подсчёта (локальная БД, приоритет видимых, общий темп запросов), прогресс в окне «Огонёк», ID в профиле, переименование в Bloodygram (пакет, классы, строки, заголовок), цвет имени по огоньку, градиент исходящих, снег в чатах, новый раздел настроек «Внешний вид» (ID / цвет имени / снег).

Сделано 28–29.09.2026 (Opus 4.8, без меня, ~2 часа): режим призрака, сохранение self-destruct медиа в галерею, ИИ в чатах через Claude API (пересказ чата, варианты ответа, объяснить сообщение/голосовое), расшифровка голосовых без Premium на устройстве, отложенное «удалить у всех», своя тема на аккаунт, Bloodygram Wrapped, чёрные кнопки голосовых, плавный набор/рассыпание букв, искры при отправке, свои иконка+экран входа, эффекты фона чата (снег/угли/искры).

Сделано 29.09.2026 (я, код-ревью + доработки): проверил три коммита Opus code-review'ом (8 подтверждённых находок), починил: миграцию `chatParticles` (старые индексы Embers/Ash/Sparks съезжали при апгрейде — теперь читаются один раз под старым ключом и переносятся под новым `chatParticles2`), краши от `getParentActivity()==null` в AI-диалогах (пользователь мог уйти с экрана, пока ждали ответ ИИ/скачивание файла — теперь есть null-чек до и после колбэка), утечку `NotificationCenter`-наблюдателя в `BloodyTranscriber.withFile` при обрыве загрузки файла (добавлен таймаут 30 с), и главное — саму жалобу «transcribe не работает»: on-device `SpeechRecognizer` реально падал с `LANGUAGE_PACK_ERROR`/`Locale not supported` (был в логах предыдущей сессии), Opus уже почти всё исправил (триггерит докачку пакета вместо тихого падения), я это проверил по коду и логам. Добавил тему фона чата «Топографическая карта» (контурные линии красным на чёрном, marching squares по noise-полю, дрейфует, кэшируется раз в ~90 мс) и тумблер «жидкое стекло» (существующий в апстриме, но не включённый по умолчанию `LiteMode.FLAG_LIQUID_GLASS` — ярче/прозрачнее блюр шапки и панелей). Проверил живьём на эмуляторе: топокарта рендерится и дрейфует, пыль от стёртых букв больше не «зависает» на месте перед тем как разлететься, жидкое стекло включается. **Не проверил живьём**: сам транскрайб голосового (не нашёл в старых чатах воспроизводимый voice-бабл, а long-press через adb не открывает контекстное меню в этой сборке — см. пункт выше). Отказался делать бесплатный просмотр платных медиа за Stars — вне правил.

Сделано 29.09.2026 (я, доводка топокарты и новый курсор): топокарта была слишком угловатая и монотонно уезжала в сторону — сделал круглее (`Paint.Join/Cap.ROUND` + толще линия + два блендированных октава noise) и заменил дрейф на орбиту фиксированного радиуса в noise-пространстве, так что узор крутится на месте, а не съезжает с экрана; добавил реакцию на свайпы/скролл чата (прокинул `SizeNotifierFrameLayout.backgroundTranslationY` в `TopoMap.draw()`). Новый `BloodyCaret` — курсор ввода толще штатного и плавно скользит по горизонтали (экспоненциальное сглаживание) вместо мгновенного прыжка; смена строки — снап без анимации. Сам поймал и починил до сборки: риск бесконечного цикла перерисовки (`setAllowDrawCursor` безусловно вызывает `invalidate()`, звать только при реальной смене состояния) и неверную математику позиции курсора (сверил точно с `EditTextBoldCursor.updateCursorPosition()`). Проверено живьём на эмуляторе: топокарта заметно круглее, не уезжает за несколько минут наблюдения, узор меняется при скролле чата; курсор рендерится нужной толщины и в правильной позиции после ввода текста и после перемещения стрелками (плавность самой анимации по статичным скриншотам не проверить — код-путь и лежащая в основе математика подтверждены).

Сделано 29.09.2026 (я, независимое движение топокарты + плавающий курсор по обеим осям): предыдущая версия топокарты (коммит 213bd1e) была статичной — не двигалась вообще. Вернул анимацию, но по-новому: раньше был единый общий дрейф/орбита всего noise-поля (весь узор «уезжал» или крутился одним листом); теперь у каждого узла сетки своя фаза/направление/радиус орбиты в noise-пространстве, взятые из второго низкочастотного noise-поля (`flowAngle`/`flowRadius` в `TopoMap`) — соседние области карты «дышат»/крутятся независимо друг от друга, а не одним сдвигом. Контуры (marching squares) пересчитываются раз в 90 мс (`REBUILD_MS`) в кэшируемый Bitmap — дороже нельзя, а на глаз плавно. `BloodyCaret`: раньше по вертикали был мгновенный снап («так лучше выглядит» — решение прошлой сессии), теперь сглаживается и вертикаль тоже (той же экспоненциальной функцией `approach()`, высота курсора при этом фиксирована, чтобы не «дышал» по толщине) + снизил скорость сглаживания (`SPEED` 0.022→0.012, `MIN_SPEED_DP` 1.6→0.55) — раньше почти не было заметно из-за высокого пола скорости, теперь курсор ощутимо «плывёт» к цели на любых перемещениях, включая смену строки. Проверено живьём на эмуляторе: два скриншота чата с топокартой с разницей 2 сек показывают явно разную форму контуров в разных частях экрана (не параллельный сдвиг); курсор в поле ввода в правильной позиции после ввода текста. **Не проверено**: сама плавность анимации курсора между кадрами (статичными скриншотами не поймать быстрое движение — только код-путь и заниженный порог скорости подтверждают, что дёрганности как раньше быть не должно).

Сделано 29.09.2026 (я, настройка камеры для видео-кружков): добавил `BloodyConfig.roundCameraMode` (запоминать последнюю / всегда фронтальная / всегда задняя, слайдер в настройках «Round video camera», под «Bubble corners») + `lastRoundCameraFront`. У Telegram здесь две параллельные реализации записи кружка (`InstantCameraView` — Camera1/Camera2 API, старая; `InstantCameraView2` — новый `RoundVideoSession`, включается флагом `SharedSettings.roundVideoCamera2Enabled`, который стоит `true` только в debug-сборках) — хукнул обе, иначе на debug-сборке (которой и тестируем) настройка работала бы только для половины путей. v2 уже имела своё «запоминание» через `SharedSettings.roundVideoLastCamera`, но не имела вариантов «всегда фронт/зад» — не стал заводить второе хранилище, `BloodyConfig.roundCameraStartsFront()` стал единой точкой решения для обеих реализаций, `setLastRoundCameraFront()` пишется при каждом переключении камеры в обеих. Проверено живьём на эмуляторе: нашёл путь в настройки (в этой версии Telegram нет drawer-меню и нижний таббар с иконками Chats/Contacts/Settings/Profile — это чужой элемент, не кликабельный, полупрозрачно наложенный поверх экрана, скорее всего от стороннего плагина «Каталог»/рекламной интеграции, а не наша навигация; реальный путь — системные Settings → пункт «Bloodygram» вверху списка), слайдер переключается и визуально, и в SharedPreferences. **Не проверено**: сам эффект при реальной записи кружка (long-press на кнопку кружка не синтезируется через adb в этой сборке — известное ограничение, см. выше).

---

# Roadmap

## Этап 0 — Форк собирается и запускается
- [x] Клон DrKLO/Telegram 12.10.5 с сабмодулями, git-remote `upstream`
- [x] Установить NDK 27.2.12479018 + CMake 3.22.1 через sdkmanager
- [x] Ключи через `local.properties` → `BuildConfig`
- [x] `applicationId` → `com.epicgram.messenger`, `AppName` → «Bloodygram» во всех локалях
- [x] Переименование Epicgram → Bloodygram (пакет `com.bloodygram`, классы `Bloody*`, строки, хуки `// Bloodygram`, заголовок списка чатов)
- [x] `applicationId` → `com.bloodygram.messenger`
- [x] Своя иконка (пока самолётик в кровавом круге — см. «Следующие задачи»)
- [x] Firebase-конфиги → заглушки; выключены `CHECK_UPDATES`, `SUPPORTS_PASSKEYS`, `SAFETYNET_KEY`
- [ ] Проверить остальные «официальные» вещи: Google Auth client id, биллинг/Stars, ссылки на Play Store
- [x] Debug-сборка собирается (arm64, ~2 мин инкрементально)
- [x] Debug-сборка ставится на телефон и в эмулятор, логин работает
- [x] Каркас: `BloodyConfig`, `BloodyStrings`, экран настроек в меню настроек

## Этап 1 — Удалённые сообщения и история правок
- [x] Перехват удаления в `processUpdateArray`, сообщение остаётся в кэше Telegram
- [x] Своя база `epicgram_<userId>.db` → `deleted_messages (channel_id, mid, deleted_at)`
- [x] UI: «удалено» рядом со временем в `ChatMessageCell`
- [ ] **Проверить на устройстве**: не пропадают ли удалённые при повторном открытии чата / подгрузке истории с сервера. Если пропадают — хранить сериализованное сообщение у себя и подмешивать в `ChatActivity` при загрузке
- [ ] Удаления «у себя» (когда удаляю сам) — удалять по-настоящему (сейчас собственные удаления с других устройств тоже помечаются)
- [ ] Иконка корзины вместо текста, красный цвет пометки
- [x] История правок: `epicgram_edits_<userId>.db`, пункт «История правок» в контекстном меню (только текст)
- [ ] Самоуничтожающиеся/одноразовые медиа — сохранять копию (опционально, отдельный тумблер)
- [x] Тумблеры в настройках: сохранять удалённые / сохранять правки
- [ ] Исключения по чатам, очистка сохранённого
- [ ] Известное ограничение: сохраняется только то, что клиент успел получить до удаления

## Этап 2 — Огоньки (стрики)
- [x] Серия дней подряд, когда **оба** писали в личке (боты/удалённые/сервисные исключены)
- [x] Своя база `epicgram_streaks_<userId>.db`, инкремент по `didReceiveNewMessages`
- [x] Первичный расчёт по серверной истории — фоново, раз в день на диалог, с лимитами
- [x] Векторный огонёк как в плагине, уровни цвета, серый = под угрозой; после имени в списке чатов, в шапке, в профиле
- [x] Полный перенос плагина: окно «Огонёк», строка в профиле, пункт меню, статистика, настройки показа, «Пересчитать все стрики»
- [x] Скан — алгоритм плагина (день за днём назад, прыжок `offset_date`), число сверено с плагином (175 = 175, в т.ч. после переделки 28.09)
- [x] Список чатов обновляет огонёк после досчёта (`listHash` в `DialogUpdateHelper`)
- [x] Ускорение: локальная БД Telegram, приоритет видимых чатов, 3 чата параллельно, пробы по 3 дня, общий темп 500 мс
- [x] Прогресс подсчёта в окне «Огонёк» (полоска + «проверено N дней»)
- [x] Цвет имени по уровню огонька (список, шапка, профиль), тумблер
- [x] Удалённые сообщения работают (в т.ч. при закрытом приложении через keep-alive)
- [x] Локальное уведомление вечером «огонёк погаснет» (`BloodyStreakReminder`)
- [x] «Мои огоньки» (топ серий), заморозка (тумблер)
- [ ] Помнить: огонёк локальный, собеседник на обычном Telegram его не видит

## Этап 3 — Дизайн и плавность
- [x] Пружинные анимации переходов экранов и сообщений в чате (`BloodyMotion`, тумблер «Пружинные анимации»)
- [x] Blur под шапкой и нижней панелью — тумблер + «жидкое стекло» (`BloodyMotion`, `LiteMode.FLAG_LIQUID_GLASS`)
- [x] Настраиваемые скругления пузырей (0/4/8/12/17)
- [x] Чёрно-красная тема «Bloodygram» по умолчанию (генератор `Tools/bloodygram/make_theme.js`)
- [x] Градиентные красно-чёрные исходящие, переливаются при скролле
- [x] Падающий снег / угли / искры / топографическая карта на фоне чата (слайдер «Эффект фона чата»)
- [x] ID собеседника/чата в профиле (тумблер)
- [x] Чёрные кнопки голосовых в исходящих (`chat_outLoader`)
- [x] Плавный набор текста и рассыпание стёртых букв (проверено живьём 29.09 — пыль больше не «зависает» перед разлётом)
- [x] Искры при отправке сообщения (тумблер «Искры при отправке»)
- [x] Своя иконка приложения и экран входа
- [x] Свой шрифт сообщений (набор системных, без своего файла шрифта)
- [ ] Пройтись по остальным экранам (настройки, профиль, медиа), подправить `OVERRIDES`
- [ ] Более плавный скролл / переходы между экранами, отключаемые «тяжёлые» эффекты для слабых телефонов
- [ ] Анимированный курсор ввода (плавно двигается, не прыгает) — сознательно не делали: это глубокий хук в `EditTextBoldCursor`, общий для всех текстовых полей приложения, риск регрессии выше пользы; можно вернуться, если попросят отдельно

## Этап 4 — Релиз
- [x] Свой GitHub-репо (GPL), README, CI-сборка APK (GitHub Actions) — spookwy/Bloodygram, v1.0.0
- [x] Release keystore, подпись, версии `Bloodygram x.y (TG 12.x.y)`
- [x] OTA-проверка обновлений через GitHub Releases
- [ ] Канал в Telegram для релизов

## Этап 5 — Приватность и ИИ (добавлено 28–29.09.2026)
- [x] Режим призрака (не отправлять «прочитано»/«онлайн»/«печатает», per-account, ⋮-меню списка чатов + настройки)
- [x] Сохранение self-destruct/одноразовых медиа в галерею (папка Bloodygram)
- [x] Скрытые/запароленные чаты, PIN
- [x] Отложенное «удалить у всех» (1мин/5мин/1час/1день)
- [x] ИИ в чатах через Claude API: пересказ чата, варианты ответа, объяснить сообщение/голосовое (свой ключ в настройках)
- [x] Расшифровка голосовых в текст без Premium, на устройстве (Android 13+)
- [x] Своя тема на аккаунт
- [x] Bloodygram Wrapped (статистика года по локальному кэшу)
- [ ] Paid Media (Stars) бесплатно/заблюренно — отказано, вне правил (обход платёжной системы Telegram)

## Идеи на потом
- Плагины
