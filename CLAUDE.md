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
- `com.bloodygram.chat.BloodyTranscriber` — расшифровка голосовых/видеосообщений в текст без Premium, на устройстве (Android 13+, `SpeechRecognizer`). Декодирует voice/round-video в PCM 16 кГц через `MediaCodec`, отдаёт в `SpeechRecognizer.createOnDeviceSpeechRecognizer` с `EXTRA_AUDIO_SOURCE`=pipe. **On-device распознавание не читает аудио-источник, если языковой пакет не скачан** — тогда `onError` триггерит `triggerModelDownload` и просит повторить через минуту (не молча падает и не уходит в `NetworkSpeechRecognizer`, который с этим pipe не работает вообще — see `ERROR_LANGUAGE_UNAVAILABLE`/`ERROR_LANGUAGE_NOT_SUPPORTED` в `onError`). Язык — настройка `transcribeLang` (пусто = язык телефона). `withFile` ждёt загрузку файла с таймаутом 30 с (иначе завис бы спиннер/утекал наблюдатель, если `fileLoaded`/`fileLoadFailed` не пришли).
- `com.bloodygram.chat.BloodyMessageMenu` / `com.bloodygram.ai.BloodyAiChat` — пункты меню сообщения («Расшифровать», «✨ Объяснить») и меню чата («✨ Пересказать чат», «✨ Варианты ответа»), плюс `com.bloodygram.ai.BloodyAi` — тонкий клиент Anthropic Java SDK (`claude-opus-5` по умолчанию, ключ юзера в настройках, `server-side-fallback` бета для opus/fable). Диалоги строятся через `fragment.getParentActivity()`, с null-чеком до и после асинхронного вызова (фрагмент мог уйти со сцены, пока ждали ответ/скачивание файла).
- `com.bloodygram.chat.BloodyAutoDelete` — «Удалить у всех через…» из меню отправки (1мин/5мин/1час/1день): следующее отправленное в этот чат сообщение планируется на авто-удаление (`deleteMessages(forAll=true)`), очередь переживает перезапуск (SharedPreferences + `AlarmManager`/`BroadcastReceiver` будят процесс, если приложение не запущено).
- `com.bloodygram.ui.BloodyAccounts` — своя тема на аккаунт: применяется в `LaunchActivity.switchToAccount` после переключения (**не** применяется при холодном старте в уже выбранный аккаунт — известное ограничение, см. «Следующие задачи»).
- `com.bloodygram.stats.BloodyWrapped`/`BloodyWrappedUi` — «Bloodygram Wrapped», статистика года по локально закэшированным сообщениям (топ собеседники, топ эмодзи, самые активные часы и т.п.), экран из настроек.
- `com.bloodygram.ui.BloodyFx` — визуальные эффекты: искры при отправке (`onSend`, ударная волна + частицы к кнопке отправки), фон чата — снег/угли/искры/**топографическая карта** (`chatParticles`: `PARTICLES_SNOW/ASH/SPARKS/TOPO`). **Топокарта** — контурные линии (marching squares по дешёвому value-noise полю) красным на чёрном, поле медленно дрейфует по диагонали; пересчитывается раз в ~90 мс в закэшированный Bitmap (не каждый кадр — иначе дорого), сам блит на канвас идёт каждый кадр. Полностью красит фон в чёрный (не оверлей поверх обоев, как снег/угли/искры).
- `com.bloodygram.ui.BloodyMotion` — пружинная интерполяция переходов/анимаций сообщений (`springAnimations`), тумблер блюра шапки/панелей (Telegram’s `FLAG_CHAT_BLUR`) и **«жидкое стекло»** (`FLAG_LIQUID_GLASS` — существующий, но не включённый по умолчанию флаг апстрима: ярче и прозрачнее тонировка блюра); включение liquid glass само включает блюр, если он был выключен.
- Стиль кода — как в Telegram: Java, без лишних абстракций, `AndroidUtilities.dp()`, `Theme.getColor()`.

## Git

- Коммиты на `main` поверх `upstream/master` 12.10.5 (локально, не запушено).
- В `git stash` лежит старая запись `epic-wip` — резервная копия раннего состояния, давно устарела; можно удалить (`git stash drop`).

## Обновление от апстрима

- `origin` → наш GitHub, `upstream` → DrKLO/Telegram.
- Обновление: `git fetch upstream` → `git merge <тег/коммит релиза>` → конфликты ищем по `// Bloodygram`.
- Клон изначально shallow; перед первым мерджем: `git fetch --unshallow upstream`.

---

# Следующие задачи

- Своя иконка (не бумажный самолётик) — сейчас в UI остались логотипы Telegram (напр. `telegram_logo_2` в `DialogStoriesCell`).
- Проверить удалённые сообщения после повторного открытия чата (см. Этап 1).
- Per-account тема (`BloodyAccounts`) не применяется при холодном старте в уже выбранный аккаунт — только при явном переключении через `switchToAccount`. Мелкий баг, не чинили из-за риска зацепить порядок восстановления темы при старте.
- Paid Media (Stars) — пользователь просил показывать платные медиа бесплатно/заблюренными. **Отказано**: это обход платёжной системы Telegram (сервер просто не отдаёт файл без оплаты), риск бана аккаунта, вне правил — не делать.
- Обход упирался в неудобство ручного тестирования через adb (`input swipe X Y X Y <duration>` с одинаковыми координатами эмулятор/эта сборка Telegram трактует как «войти в мультивыбор», а не как long-press → контекстное меню сообщения). Если нужно тестить контекстное меню сообщения через adb — искать другой способ синтезировать long-press (например `sendevent` с ощутимым дрожанием координат, или через uiautomator).

Сделано 28.09.2026 (я): баг «0» в списке, ускорение подсчёта (локальная БД, приоритет видимых, общий темп запросов), прогресс в окне «Огонёк», ID в профиле, переименование в Bloodygram (пакет, классы, строки, заголовок), цвет имени по огоньку, градиент исходящих, снег в чатах, новый раздел настроек «Внешний вид» (ID / цвет имени / снег).

Сделано 28–29.09.2026 (Opus 4.8, без меня, ~2 часа): режим призрака, сохранение self-destruct медиа в галерею, ИИ в чатах через Claude API (пересказ чата, варианты ответа, объяснить сообщение/голосовое), расшифровка голосовых без Premium на устройстве, отложенное «удалить у всех», своя тема на аккаунт, Bloodygram Wrapped, чёрные кнопки голосовых, плавный набор/рассыпание букв, искры при отправке, свои иконка+экран входа, эффекты фона чата (снег/угли/искры).

Сделано 29.09.2026 (я, код-ревью + доработки): проверил три коммита Opus code-review'ом (8 подтверждённых находок), починил: миграцию `chatParticles` (старые индексы Embers/Ash/Sparks съезжали при апгрейде — теперь читаются один раз под старым ключом и переносятся под новым `chatParticles2`), краши от `getParentActivity()==null` в AI-диалогах (пользователь мог уйти с экрана, пока ждали ответ ИИ/скачивание файла — теперь есть null-чек до и после колбэка), утечку `NotificationCenter`-наблюдателя в `BloodyTranscriber.withFile` при обрыве загрузки файла (добавлен таймаут 30 с), и главное — саму жалобу «transcribe не работает»: on-device `SpeechRecognizer` реально падал с `LANGUAGE_PACK_ERROR`/`Locale not supported` (был в логах предыдущей сессии), Opus уже почти всё исправил (триггерит докачку пакета вместо тихого падения), я это проверил по коду и логам. Добавил тему фона чата «Топографическая карта» (контурные линии красным на чёрном, marching squares по noise-полю, дрейфует, кэшируется раз в ~90 мс) и тумблер «жидкое стекло» (существующий в апстриме, но не включённый по умолчанию `LiteMode.FLAG_LIQUID_GLASS` — ярче/прозрачнее блюр шапки и панелей). Проверил живьём на эмуляторе: топокарта рендерится и дрейфует, пыль от стёртых букв больше не «зависает» на месте перед тем как разлететься, жидкое стекло включается. **Не проверил живьём**: сам транскрайб голосового (не нашёл в старых чатах воспроизводимый voice-бабл, а long-press через adb не открывает контекстное меню в этой сборке — см. пункт выше). Отказался делать бесплатный просмотр платных медиа за Stars — вне правил.

---

# Roadmap

## Этап 0 — Форк собирается и запускается
- [x] Клон DrKLO/Telegram 12.10.5 с сабмодулями, git-remote `upstream`
- [x] Установить NDK 27.2.12479018 + CMake 3.22.1 через sdkmanager
- [x] Ключи через `local.properties` → `BuildConfig`
- [x] `applicationId` → `com.epicgram.messenger`, `AppName` → «Bloodygram» во всех локалях
- [x] Переименование Epicgram → Bloodygram (пакет `com.bloodygram`, классы `Bloody*`, строки, хуки `// Bloodygram`, заголовок списка чатов)
- [x] `applicationId` → `com.bloodygram.messenger`
- [ ] Своя иконка (не бумажный самолётик)
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
- [ ] Локальное уведомление вечером «огонёк погаснет»
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
- [ ] Свой GitHub-репо (GPL), README, CI-сборка APK (GitHub Actions)
- [ ] Release keystore, подпись, версии `Bloodygram x.y (TG 12.x.y)`
- [ ] Канал в Telegram для релизов, OTA-проверка обновлений через свой канал/GitHub Releases

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
