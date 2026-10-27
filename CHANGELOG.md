# Changelog

Versions follow [SemVer](https://semver.org/). Android `versionCode = major*10000 + minor*100 + patch`.
Entries from 1.0.1 on are written in Russian: the app shows them to its owner in «Обновления».

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
