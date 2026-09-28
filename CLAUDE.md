# Epicgram (→ переименовываем в Bloodygram, см. «Следующие задачи»)

Кастомный Android-клиент Telegram. Фишки: сохранение удалённых сообщений и истории правок (как AyuGram), огоньки-стрики в чатах (перенос плагина «Огонёк»), чёрно-красный дизайн.

Пользователь общается по-русски, неформально; хочет, чтобы Claude делал всё сам (сборка, установка, проверка на эмуляторе), а не выдавал команды.

## Как работаем (цикл разработки)

- **Запуск на ПК:** `powershell -ExecutionPolicy Bypass -File Tools\epicgram\run.ps1` — собирает x86_64-debug, поднимает эмулятор AVD `epic` (если не запущен, `-gpu host`), ставит (`adb install -r -t`) и запускает. В эмуляторе пользователь **уже залогинен в свой аккаунт** — можно проверять всё вживую (чаты, огоньки, профиль).
- `run.ps1 -Phone` — установочный APK для телефона → `Desktop\Epicgram-debug.apk`.
- Скриншоты/логи с эмулятора: `adb exec-out screencap -p > file.png`, `adb logcat -d`, `adb shell uiautomator dump`. В Git Bash для `adb shell` с путями `/sdcard/...` нужен `MSYS_NO_PATHCONV=1` (но **не** для `gradlew`). Тап: `adb shell input tap X Y` (экран 1080×2400).
- Эмулятор с `-gpu swiftshader_indirect` падает (exit 139) — только `-gpu host`.
- Ненадёжные ограничения окружения: удаление содержимого рабочей папки через `rm -rf` блокируется защитой Claude Code.

## База

- Форк **официального Telegram for Android** — https://github.com/DrKLO/Telegram (стартовали с 12.10.5).
- exteraGram не используем: публичный репо заархивирован и застрял на 9.6.6 (2023).
- Лицензия **GPL-2.0** — исходники Epicgram обязаны быть публичными.
- Требования Telegram к сторонним клиентам: свой `api_id`, не называться «Telegram», не использовать их логотип.

## Сборка

- Android Studio 2025.1.4+, **NDK 27.2.12479018**, Android SDK 36, JDK 17/21.
- Сабмодули: `git -c core.longpaths=true submodule update --init --recursive --depth=1` (на Windows без `core.longpaths` падает сабмодуль `TMessagesProj_Modules/media`).
- Ключи: `EPIC_APP_ID` / `EPIC_APP_HASH` в `local.properties` (в `.gitignore`) → `BuildConfig.EPIC_APP_ID/EPIC_APP_HASH` (задаются в `TMessagesProj/build.gradle`) → `BuildVars.APP_ID/APP_HASH`. Получить на https://my.telegram.org. Реальные ключи **не коммитить**.
- Подпись: `TMessagesProj/config/release.keystore` + пароли в `gradle.properties` — пока dummy из апстрима; для релиза заменить на свои.
- `google-services.json` во всех модулях — заглушки (placeholder-проект). Пуши через FCM не работают, пока не заведём свой Firebase-проект.
- Сборка debug для установки на телефон: `./gradlew :TMessagesProj_App:assembleAfatDebug`
  APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`, пакет `com.epicgram.messenger.beta`.
- Быстрая проверка компиляции (только arm64): добавить `-Pandroid.injected.build.abi=arm64-v8a`. **Такой APK помечен `testOnly=true`** и ставится только через `adb install -t`, файлом на телефон — «пакет недействителен». Лежит в `build/intermediates/apk/...`.
- Подпись debug сейчас — dummy `release.keystore` из апстрима (публичный ключ, Play Protect ругается). Перед раздачей людям — свой keystore, иначе потом придётся переустанавливать с потерей данных.
- Модули: `TMessagesProj` — весь код клиента (library); `TMessagesProj_App` — обёртка-приложение, которую собираем. Остальные `TMessagesProj_App*` (Huawei, HockeyApp, Standalone, Tests) не трогаем.

## Правила кода

- **Минимум правок в файлах апстрима.** Вся наша логика — в отдельном пакете `TMessagesProj/src/main/java/com/epicgram/`. В файлах Telegram — только короткие хуки-вызовы в наш код.
- Каждый хук в апстрим-файле помечаем комментарием `// Epicgram` (или блоком `// Epicgram start` / `// Epicgram end`), чтобы легко находить при мердже обновлений.
- `namespace` (`org.telegram.messenger`) не трогаем — меняем только `applicationId`, иначе переписывать тысячи импортов.
- Настройки Epicgram — отдельный `SharedPreferences` (`epicgram_config`), класс `com.epicgram.EpicConfig`. Экран — `com.epicgram.ui.EpicSettingsActivity` (UniversalFragment), вход — пункт id `1000` в `SettingsActivity`.
- Строки — `res/values/strings_epicgram.xml` (англ.) и `res/values/strings_epicgram_ru.xml` (те же имена с суффиксом `_ru`), префикс `Epic`. **Не в `values-ru/`**: app-модуль собирается с `localeFilters "zz"`, все локализованные ресурсы вырезаются. Читать **только через `com.epicgram.EpicStrings.get/format`** — берёт `_ru` по языку Telegram; `LocaleController.getString` их не видит. Склонение дней — `EpicStrings.days(n)`.
- Данные Epicgram — свои SQLite-базы на аккаунт (`epicgram_<userId>.db`, `epicgram_edits_<userId>.db`, `epicgram_streaks_<userId>.db`), в базу Telegram не пишем. Синглтоны по аккаунту пересоздаются при смене userId.
- **Переводы строк:** в репо `core.autocrlf=false` + `.gitattributes` (`* -text`). Глобальный autocrlf=true на этой машине превращал `assets/*.attheme` в CRLF → парсер тем Telegram ломался (в тёмной теме чёрный текст на чёрном). Ассеты не конвертировать.
- Эмулятор для проверок: AVD `epic` (Android 35, x86_64, WHPX). Быстрая сборка под него: `-Pandroid.injected.build.abi=x86_64` + `adb install -t`. Не экспортировать `MSYS_NO_PATHCONV` при запуске `gradlew` — ломает wrapper.
- Python в системе нет (только заглушка Store) — для скриптов использовать bash/node.

## Модули

- `com.epicgram.deleted.EpicDeletedMessages` — перехват `updateDeleteMessages`/`updateDeleteChannelMessages` в `MessagesController.processUpdateArray` (`continue` вместо удаления), пометка «удалено» в `ChatMessageCell.measureTime`, перерисовка через `NotificationCenter.epicMessagesMarkedDeleted` → `ChatActivity.updateVisibleRows(condition)`.
- `com.epicgram.history.EpicEditHistory` — хук после `MessageObject.getDialogId(message)` в ветке edit-апдейтов; старая версия читается из `messages_v2` на `storageQueue` Telegram (до `putMessages` новой). Пункт меню `OPTION_EDIT_HISTORY = 10001` в обоих билдерах контекстного меню `ChatActivity`.
- `com.epicgram.streaks.EpicStreaks` — дни (флаги мои/собеседника) по личкам в `epicgram_streaks_<userId>.db` (`streak_days`, `streak_sync`, `streak_stats`, версия БД 3). Скан — алгоритм плагина (`walk`/`request`, см. Этап 2), очередь `syncQueue` по одному чату, `REQUEST_DELAY = 300` мс, чат ставится в очередь лениво из `getStreak()` при первом показе, раз в день (`synced`). Инкремент по `didReceiveNewMessages`. Счётчик удалённых: `onRemoteDelete` (из `EpicDeletedMessages.interceptDelete`) и `onOwnDelete` (хук в `MessagesController.deleteMessages`). Обновление UI — `notifyUi()` → `updateInterfaces(UPDATE_MASK_NAME)` + `NotificationCenter.epicStreaksUpdated`.
- `com.epicgram.streaks.EpicStreakStats` — статистика для окна «Огонёк» (порт `StatsJob` плагина): листает историю от первого сообщения (`offset_id=mx+1, add_offset=-100, min_id=mx`), инициатива с окном 30 мин, прогресс сохраняется в `streak_stats` после каждой страницы.
- `com.epicgram.streaks.EpicStreakUi` — окно «Огонёк» (`showStats`), строка профиля (`bindProfileRow`), тап по огоньку в шапке (`HeaderTouch`), пункт меню `MENU_ID = 10002`, хелперы `listSuffix/headerTitle/profileName` с учётом настроек показа.
- `com.epicgram.streaks.EpicFire` — векторный огонёк (пути из SVG плагина «Огонёк», `C:\Users\SWAGA_PA3PEIIIEHA\Desktop\Projects\Fire Ayugram\ogonek.plugin` — эталон дизайна), уровни цвета: серый (0/под угрозой), оранжевый, 100+ красный, 200+ фиолетовый, 300+ синий, 500+ зелёный. Суффикс « 🔥N» (огонёк 1.05×, число 0.8× sans-serif-light) после имени в `DialogCell.buildLayout` (имя ellipsize-ится до огонька) и в `ChatAvatarContainer.setTitle`.
- `com.epicgram.keepalive.EpicKeepAliveService` — foreground-сервис (`remoteMessaging`) вместо FCM: хук в `ApplicationLoader.startPushService()`, включает `pushConnection`. Без него закрытое приложение не получает апдейты → удалённые не сохраняются.
- Стиль кода — как в Telegram: Java, без лишних абстракций, `AndroidUtilities.dp()`, `Theme.getColor()`.

## Git

- **Вся работа пока не закоммичена** (ветка `main` поверх `upstream/master` 12.10.5). Первым делом в новом чате предложить пользователю сделать коммит (`local.properties` в `.gitignore`, ключи не попадут).
- В `git stash` лежит старая запись `epic-wip` — резервная копия раннего состояния, рабочее дерево новее; её можно удалить (`git stash drop`) после коммита.

## Обновление от апстрима

- `origin` → наш GitHub, `upstream` → DrKLO/Telegram.
- Обновление: `git fetch upstream` → `git merge <тег/коммит релиза>` → конфликты ищем по `// Epicgram`.
- Клон изначально shallow; перед первым мерджем: `git fetch --unshallow upstream`.

---

# Следующие задачи (запрос пользователя, по порядку важности)

1. **Баг: в списке чатов огонёк «0», а в шапке уже правильный (6).** После досчёта `notifyUi()` шлёт `updateInterfaces(UPDATE_MASK_NAME)`, но `DialogCell.update(mask)` не всегда перестраивает layout (считает, что имя не менялось). Плагин решал так: в течение ~1.5 с после изменения стриков в `DialogCell.update` сбрасывал `updateHelper.lastDrawnDialogId = -1` и подменял `mask = 0` (полная перестройка). Сделать аналог: флаг/время «стрики изменились» в `EpicStreaks` и хук в начале `DialogCell.update(int mask, boolean animated)`, либо после досчёта вызывать `dialogsAdapter` refresh / `NotificationCenter.dialogsNeedReload`. Проверить на эмуляторе.
2. **Ускорить подсчёт огонька.** Идеи: (а) как в плагине — сначала локальная БД Telegram: `SELECT ((date+tzOffset)/86400), MAX(out), MIN(out) FROM messages_v2 WHERE uid=? AND date>0 GROUP BY 1 ORDER BY 1 DESC LIMIT 3000` на `MessagesStorage.getStorageQueue()` (плагин `_local_day_flags`), сервер добирать только с дня, где локальная цепочка оборвалась; (б) видимые на экране чаты — в начало очереди; (в) 2–3 параллельных скана вместо одного и `REQUEST_DELAY` 100–150 мс (следить за FLOOD_WAIT).
3. **Прогресс-бар подсчёта огонька в окне «Огонёк»** (сейчас просто «Огонёк ещё считается…»): отдавать из скана текущий проверенный день/число дней (`scan.today - scan.day`) через `epicStreaksUpdated`, в окне — `LinearProgressIndicator`/полоска + текст «проверено N дней».
4. **ID собеседника в профиле, как в AyuGram**: отдельная строка в блоке информации (под телефоном/юзернеймом) — «1329248998 / ID», по тапу копировать. В `ProfileActivity` так же, как `epicStreakRow`: поле `epicIdRow`, сброс в `updateRowsIds`, добавить после `usernameRow`, тип `VIEW_TYPE_TEXT_DETAIL`, bind через `TextDetailCell.setTextAndValue(id, "ID", divider)`, клик → `AndroidUtilities.addToClipboard` + bulletin. Для чатов/каналов — тоже (id `-100…`).
5. **Переименовать в Bloodygram**: `AppName`/`AppNameBeta` во всех `res/values*/strings.xml` (сейчас «Epicgram»), строки `EpicSettings*`, заголовок уведомления keep-alive, настройки. **Заголовок «Telegram» над списком чатов** берётся в `DialogsActivity` (~стр. 3517 `getString(R.string.AppName)`) через `LocaleController` → `AppName` исключён из генерации строк (`TelegramStringsTask.GENERATED_EXCLUSIONS`) и подменяется облачной строкой «Telegram»; заменить на `EpicStrings`/константу «Bloodygram». Поискать другие места `R.string.AppName` в UI. Пакет/папку/классы `com.epicgram` можно оставить (внутреннее имя), либо переименовать отдельно — решить с пользователем.
6. **Цвет имени чата по уровню огонька** (как сам огонёк: серый/оранжевый/красный/фиолетовый/синий/зелёный) — в списке чатов (`DialogCell`: `ForegroundColorSpan` на `nameStringFinal` или цвет `nameLayout` paint), в шапке (`ChatAvatarContainer.setTitle`) и в профиле. Цвет брать из `EpicFire.TIER_COLORS` (напр. цвет верха градиента), серый — не красить. Тумблер в настройках.
7. **Градиентные красно-чёрные исходящие сообщения, «переливаются» при скролле** — встроено в Telegram: градиент исходящих пузырей привязан к экрану и сдвигается при скролле. В `Tools/epicgram/make_theme.js` добавить в `OVERRIDES` ключи `chat_outBubbleGradient1/2/3` (красный → тёмно-красный → почти чёрный) и `chat_outBubbleGradientAnimated=1` (ключи есть в `Theme.java` ~2834, 2912), перегенерировать тему, проверить читаемость текста (`chat_messageTextOut` белый).
8. **Падающий снег на фоне чата, как новогодний в Telegram**: есть `ui/Components/SnowflakesEffect.java`, уже используется в `SizeNotifierFrameLayout` (фон чата) и `ActionBar` — там включается по празднику (`Theme.canStartHolidayAnimation()` / похожее). Включить всегда (или тумблером в настройках Epicgram) для фона чата.
9. Записать итоги сюда же и отметить в Roadmap.

---

# Roadmap

## Этап 0 — Форк собирается и запускается
- [x] Клон DrKLO/Telegram 12.10.5 с сабмодулями, git-remote `upstream`
- [x] Установить NDK 27.2.12479018 + CMake 3.22.1 через sdkmanager
- [x] Ключи через `local.properties` → `BuildConfig` (вписать свои `EPIC_APP_ID`/`EPIC_APP_HASH`)
- [x] `applicationId` → `com.epicgram.messenger`, `AppName` → «Epicgram» во всех локалях
- [ ] Своя иконка (не бумажный самолётик)
- [x] Firebase-конфиги → заглушки; выключены `CHECK_UPDATES`, `SUPPORTS_PASSKEYS`, `SAFETYNET_KEY`
- [ ] Проверить остальные «официальные» вещи: Google Auth client id, биллинг/Stars, ссылки на Play Store
- [x] Debug-сборка собирается (arm64, ~2 мин инкрементально)
- [x] Debug-сборка ставится на телефон и в эмулятор, логин работает (ключи в `local.properties` вписаны)
- [x] Каркас `com.epicgram`: `EpicConfig`, `EpicStrings`, экран «Настройки Epicgram» в меню настроек

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
- [x] `com.epicgram.streaks.EpicStreaks`: серия дней подряд, когда **оба** писали в личке (боты/удалённые/сервисные исключены)
- [x] Своя база `epicgram_streaks_<userId>.db` (`streak_days`, `streak_sync`), инкремент по `didReceiveNewMessages`
- [x] Первичный расчёт по серверной истории (`messages.getHistory`) — фоново, раз в день на диалог, с лимитами
- [x] Векторный огонёк как в плагине, уровни цвета, серый = под угрозой; после имени в списке чатов
- [x] Огонёк в шапке чата (`ChatAvatarContainer`)
- [x] Полный перенос плагина: `EpicStreakUi` (окно «Огонёк» — инициатива 30 мин, статистика, удалённые; тап по огоньку в шапке через `ChatAvatarContainer.dispatchTouchEvent`; строка `epicStreakRow` и огонёк у имени в `ProfileActivity`; пункт меню `MENU_ID = 10002` в ⋮ чата и профиля), `EpicStreakStats` (листание истории от первого сообщения, прогресс в `streak_stats`), настройки показа, «Пересчитать все стрики»
- [x] Синк стриков ленивый, как в плагине: чат ставится в очередь при первом показе, раз в день; FLOOD_WAIT выдерживается
- [x] Скан стрика — алгоритм плагина (`EpicStreaks.walk/request`): идём по дням назад, засчитанный день пропускаем прыжком `offset_date = midnightOf(day+1)`, уже известные «оба писали» дни берём из кэша без запросов, лимит 400 запросов. (Старый скан упирался в 4000 сообщений → в активном чате было 20 вместо 175.)
- [x] Проверено на устройстве: окно статистики, строка и огонёк в профиле, огонёк в шапке работают; число сверено с плагином (175 = 175 после фикса скана)
- [x] Удалённые сообщения работают (в т.ч. при закрытом приложении через keep-alive)
- [ ] Локальное уведомление вечером «огонёк погаснет»
- [x] Настройки: вкл/выкл, где показывать (список/шапка/профиль/строка), минимальный стрик (0…100, по умолчанию 0 как в плагине), «Пересчитать все стрики»
- [ ] Помнить: огонёк локальный, собеседник на обычном Telegram его не видит

## Этап 3 — Дизайн и плавность
- [ ] Пружинные анимации (`androidx.dynamicanimation` SpringAnimation) для открытия чатов, появления сообщений, переключения вкладок
- [ ] Blur под шапкой и нижней панелью (у Telegram частично есть — расширить)
- [ ] Настраиваемые скругления пузырей, аватарок, отступы
- [x] Чёрно-красная тема «Epicgram» по умолчанию: `assets/epicgram.attheme` генерируется `node Tools/epicgram/make_theme.js` из `night.attheme` (синее → красное, фоны → чёрные, ключевые цвета в `OVERRIDES`). Регистрация в `Theme.java` после «Night», один раз ставится дневной и ночной (`epicThemeDefaultApplied`), в `isDark()` и `applyDayNightThemeMaybe` — исключения для «Epicgram» (иначе днём при авто-ночи Telegram подменял её на Blue)
- [x] Тема проверена на эмуляторе после входа (список чатов, чат, окно огонька) — выглядит как задумано
- [ ] Пройтись по остальным экранам (настройки, профиль, медиа), подправить `OVERRIDES`
- [ ] Более плавный скролл / переходы между экранами, отключаемые «тяжёлые» эффекты для слабых телефонов
- [ ] Свой шрифт (опционально)

## Этап 4 — Релиз
- [ ] Свой GitHub-репо (GPL), README, CI-сборка APK (GitHub Actions)
- [ ] Release keystore, подпись, версии `Epicgram x.y (TG 12.x.y)`
- [ ] Канал в Telegram для релизов, OTA-проверка обновлений через свой канал/GitHub Releases

## Идеи на потом
- Режим призрака (не отправлять «прочитано» / «онлайн» / «печатает»)
- Плагины
