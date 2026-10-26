# Configuration

## Server environment (`deploy/.env`)

| Variable | Meaning |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | database credentials (internal network only) |
| `ANTHROPIC_API_KEY` | Claude API key; lives only on the server, never in the APK |
| `ANTHROPIC_PROXY` | optional outbound HTTP proxy for `api.anthropic.com` |
| `SECRET_KEY` | pepper for device-token hashes; changing it logs out every device |
| `PUBLIC_BASE_URL` | public HTTPS address |
| `DEFAULT_TIMEZONE`, `TZ` | owner time zone (schedules, quiet hours, reports) |
| `GITHUB_REPO` | repository with APK releases |
| `FCM_SERVICE_ACCOUNT_FILE`, `FCM_CLIENT_CONFIG_FILE` | Firebase files in `deploy/secrets/` |
| `FCM_USE_PROXY` | send FCM through `ANTHROPIC_PROXY` |
| `BACKUP_PASSPHRASE`, `BACKUP_KEEP_DAYS`, `BACKUP_AT`, `BACKUP_DIR` | encrypted backups |
| `LOG_LEVEL`, `DEBUG_LOG_BODIES` | logging; bodies are never logged unless the latter is `true` |

## Owner settings (edited in the app, stored in `app_settings`)

| Key | What it controls |
|---|---|
| `models` | model per purpose: `chat`, `analysis`, `background`, `import`, `vision` (ids from `/v1/models`) |
| `effort` | reasoning effort per purpose (`low`…`xhigh`) |
| `api_budget` | `monthly_limit_usd` (100), warnings 50/80/95 %, `degrade`: from 80 % low-priority background jobs pause and analysis moves to the chat model, from 95 % chat moves to the background model; 100 % blocks new paid calls |
| `initiative` | proactive messages: on/off, `max_per_day`, `min_interval_minutes`, pause, morning/evening/weekly times |
| `schedule` | wake, work, home, sleep times — drive quiet hours and the agent's sense of the day |
| `privacy` | sending images, progress photos, archive fragments, secret memories to Claude; memory types the agent may read; hiding notification text |
| `finance` | income days and plan, large-purchase threshold, savings mode, notification allow-list, FX rates, proverkacheka token, tax parameters (`regime`, `rate_pct`, `insurance_fixed_minor`, …) |
| `fitness` | training days and time, missed-workout check time, target weight, nutrition mode |
| `tool_permissions` | per-tool level overrides (A/B/C) and on/off |
| `profile_summary` | generated nightly from permanent memories |

Default model routing:

| Purpose | Model | Why |
|---|---|---|
| chat | `claude-sonnet-5-5` | everyday conversation with tools |
| analysis | `claude-opus-5-5` | psych and critic modes, weekly report, experiments |
| background | `claude-haiku-5-5` | initiative decisions, memory extraction, categorisation, follow-ups |
| import | `claude-haiku-5-5` | archive analysis in ~15k-token chunks |
| vision | `claude-haiku-5-5` | receipts and meals |

Every request uses adaptive thinking with `block_binding: drop_block` (append-only histories; stale thinking blocks
are dropped instead of failing), server-side refusal fallbacks on models that support them, and prompt caching.

## Agent permissions

| Level | Behaviour | Examples |
|---|---|---|
| A | runs automatically | search memory, save a memory, finance summary, categorise, create a task, schedule a follow-up |
| B | runs, the owner is notified, can be rolled back | edit a memory, change a task date, change a savings plan, savings mode, edit a training plan |
| C | only after confirmation in the app/notification | delete memories in bulk, draft a message to another person |

C-only tools (`min_level = C`) cannot be made automatic. Money transfers and payments do not exist as tools.

## Phone

Server URL (default `https://brain.quasar-x.ru`), biometric lock and timeout, theme, home cards, notification
allow-list (Mir Pay `ru.nspk.mirpay`, Sber `ru.sberbankmobile`, T-Bank `com.idamob.tinkoff.android`, T-Business),
Health Connect and usage-stats toggles — all in Settings.
