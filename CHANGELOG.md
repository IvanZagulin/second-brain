# Changelog

Versions follow [SemVer](https://semver.org/). Android `versionCode = major*10000 + minor*100 + patch`.

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
