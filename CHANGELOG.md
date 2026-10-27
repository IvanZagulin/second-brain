# Changelog

Versions follow [SemVer](https://semver.org/). Android `versionCode = major*10000 + minor*100 + patch`.
Entries from 1.0.1 on are written in Russian: the app shows them to its owner in «Обновления».

## [1.1.4] — 2026-10-27

- Заметки: в списке заголовок и пара строк, полный текст открывается нажатием; удаление — иконкой сразу у заголовка, с подтверждением.
- «Что я о тебе знаю»: если портретов несколько, между ними можно переключаться; любой можно удалить.

## [1.1.3] — 2026-10-27

- Тренировки: в «Добавить упражнение» база упражнений видна сразу по группам мышц, а не только после ввода названия.
- В списке программ видно, сколько в программе упражнений, а пустая так и подписана.
- Справка честно говорит, что программы нужно наполнить, и объясняет, что все диалоги видят друг друга.
- Агент в любом диалоге и в сообщениях первым знает, что ты писал в других диалогах за сутки.

## [1.1.2] — 2026-10-27

- Диалоги: меню «⋮» — закрепить, в архив, удалить; пустые диалоги скрыты и убираются одной кнопкой.
- Люди: в карточке есть «Убрать из окружения»; агент сам больше не заводит новых людей, только если попросить.
- Тренировки: кнопка «Сегодня не иду» с причиной; после отметки карточка переходит к следующей тренировке.
- Агенту можно просто написать: «хочу бросить курить» — заведёт привычку, «сегодня болел, не пошёл» — отметит пропуск, «добавь в четверг жим 3×10» — поправит программу.
- Ответ на вопрос агента теперь исправляет связанные воспоминания, а не просто ложится рядом.

## [1.1.1] — 2026-10-27

- Сводка на главной обновляется утром, днём, вечером и сразу после тренировки и знает, что время тренировки уже прошло.
- Вопрос от агента стоит сразу под сводкой, а не прячется под полем ввода.
- Уточняющие вопросы на «ты», как и сам агент.
- «Деньги»: кнопки «Хочу купить», «QR чека», «Фото чека» и «Выписка» переносятся на вторую строку, а не обрезаются у края.
- Суммы вроде «3 370 ₽» больше не разрываются переносом строки.

## [1.1.0] — 2026-10-27

- Агент в центре: на главной несколько строк от него о том, что сегодня важно, подсказки в одно касание и поле для вопроса.
- Внизу три вкладки — «Агент», «Деньги», «Тренировки»; всё остальное в меню ☰.
- Меню: пустые разделы свёрнуты в «Ещё разделы» с подсказкой, чем их наполнить.
- «Что я о тебе знаю»: портрет по импортированной переписке на отдельном экране — из меню и с главной.
- Уточняющие вопросы приходят по одному: «Ответить», «Позже» или «Не спрашивать».
- Подсказка с главной сразу открывает диалог и отправляет вопрос.
- Если устройство отключили, приложение попросит новый код, а не будет молча показывать старые данные.
- Диалоги получают нормальные названия — раньше иногда записывалось рассуждение модели.

## [1.0.3] — 2026-10-27

- Питание: кнопка «Фото» показывается, только когда включено распознавание еды по фото — иначе снимок нигде бы не использовался; рядом подсказка, где это включить.
- Фото формы подчиняются своему переключателю «Приватность → Фото формы» (раньше их пропускал общий «Изображения»).
- Питание: ошибка оценки показывается сообщением, а не превращается в пустую запись; без связи приём пищи записывается без оценки.

## [1.0.2] — 2026-10-27

- Обновления без похода на GitHub: приложение само проверяет новую версию при открытии и каждые 6 часов, по Wi-Fi заранее скачивает и проверяет её; на главной появляется карточка «Доступна версия», установка — в одно касание.
- «Новый диалог» открывается сразу, даже при плохой связи.
- Новый раздел «Как пользоваться»: что умеет агент, что он помнит и где хранятся данные (на главной и в «Разделах»).
- Подписи нижней панели всегда в одну строку — «Тренировки» больше не переносится.
- Уведомления приложения по нажатию открывают нужный экран.
- Если фото еды без описания, а распознавание фото выключено, приложение подскажет, что включить.

## [1.0.1] — 2026-10-27

- Новый дизайн в духе Claude: тёплая палитра «слоновая кость / графит» с одним терракотовым акцентом,
  антиква Source Serif 4 для заголовков и ответов агента, спокойные карточки с тонкой рамкой.
- Главная: приветствие и поле «Спросите…»; чат: ответы агента без пузыря, новое поле ввода.
- Исправлено: вкладка «Главная» могла открывать другую вкладку.
- Агент перепроверяет сообщения, отложенные на утро из-за тихих часов, и не пишет «превышен бюджет»,
  пока не введены остатки.

## [1.0.0] — 2026-10-27

First complete release.

- Android app (Kotlin, Compose): home, finance, training, chat, agent brain; tasks, habits, experiments,
  mood, notes, analytics, agent history, settings, updates, diagnostics; light/dark theme.
- Biometric lock (fingerprint, device PIN fallback), encrypted local database (SQLCipher, Keystore-wrapped key),
  offline-first sync with conflict resolution.
- Chat with Claude: streaming answers, tools with A/B/C permission levels, modes (coach, psych, finance,
  trainer, critic, companion), voice input, attachments, offline queue, replies from notifications.
- Long-term memory: five memory types, hybrid search (pgvector + Russian full-text + trigrams), evidence for
  every conclusion, people cards, life events, clarifying questions; Telegram / ChatGPT / Claude / Gemini import
  with dedupe, cost estimate and owner confirmation; source deletion with derived-data cleanup.
- Finance: Mir Pay / Sber / T-Bank notification capture, statements (Sber PDF and operation certificates,
  T-Bank business PDF, CSV/XLSX, 1C), receipts (fiscal QR, photo/PDF), duplicate matching, learned
  categorisation, personal vs business, internal transfers, cash, budget engine (free money, daily budget,
  obligations, reserves, forecast), subscriptions with detection, debts, goals, IP tax calendar, «Хочу купить».
- Training diary: plans with version history and rollback, active workout mode with rest timer, progress,
  body metrics, progress photos (local only), nutrition estimates, AI coach analysis.
- Autonomous agent on the VPS: scheduler and worker, event rules, initiative messages with daily caps and
  quiet hours, morning/evening/weekly reports, self-scheduled follow-ups, full action journal with rollback.
- Claude API budget: per-call reservations, usage per model/purpose, 50/80/95 % warnings, degradation near the
  cap, hard stop at the limit.
- Integrations: FCM push (runtime-configured) with polling fallback, Health Connect (Galaxy Watch via Samsung
  Health), app usage statistics, home-screen widgets, share sheet, in-app updates from GitHub Releases.
- Backend: FastAPI, PostgreSQL 16 + pgvector, Redis/arq, local embeddings, encrypted daily backups.
