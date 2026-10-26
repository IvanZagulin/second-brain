# Second Brain

Personal autonomous AI agent for Android: a long-term-memory companion and harsh coach built on the
Claude API, with automatic personal finance (Mir Pay / bank notifications, statements, receipts),
debts and taxes of a sole proprietorship, a training diary with an AI coach, habits, mood and tasks.
The agent runs on a VPS around the clock and writes first when something matters.

Single-user by design (every row still carries an owner id). Russian UI.

| Part | Stack |
|---|---|
| `android/` | Kotlin, Jetpack Compose, Room (SQLCipher), WorkManager, Retrofit/OkHttp, BiometricPrompt, FCM |
| `backend/` | Python 3.12, FastAPI, SQLAlchemy 2 (async), PostgreSQL 16 + pgvector, Redis |
| `agent/` | persona, tools with A/B/C permission levels, context assembly, memory extraction, initiative |
| `workers/` | arq worker + scheduler (autonomous jobs, pushes, embeddings) |
| `database/` | Alembic migrations |
| `deploy/` | Docker Compose, nginx site, backup/restore scripts |
| `docs/` | deployment, build, configuration, database, agent, security |

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) to run the backend and [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md)
to build and release the APK. Secrets, personal data and signing keys never live in this repository.
