# Security and privacy

## Secrets

- Never in the repository: API keys, the signing keystore and passwords, `.env`, Firebase files, `seed.json`,
  real chats, statements, database dumps, backups. `.gitignore` covers them; CI reads signing values from GitHub
  secrets.
- Server secrets live in `deploy/.env` (mode 600) and `deploy/secrets/` (owned by the container user, mode 700).
- The Claude API key exists only on the server. The phone never talks to Anthropic directly.

## Server

- Only `127.0.0.1:8040` is published; PostgreSQL, Redis and the embedder are reachable only on the compose
  network. TLS via nginx + Let's Encrypt; HSTS.
- Request rate limits in nginx (`limit_req`) and per device in the app (Redis counter); pairing attempts are
  limited per IP.
- Containers run as an unprivileged user with memory limits.
- Sensitive reads (raw bank notification texts) and security-relevant changes (privacy and tool permissions,
  device pairing/revocation, deletions, import consent) are written to `audit_log`.
- Logs redact keys/tokens and never contain message, notification or chat texts unless `DEBUG_LOG_BODIES=true`.

## Devices

- A phone pairs with a one-time code (`python -m app.cli pair`, valid up to 7 days, or 30 minutes when issued from
  another paired device) and receives a random 320-bit bearer token; the server stores only its HMAC.
- Lost phone: revoke it in «Устройства» from another device, or on the server:
  `docker compose exec db psql -U brain -d brain -c "UPDATE devices SET revoked_at = now() WHERE name = '…'"`.
- New phone / biometrics unavailable: get a new code from the server CLI and pair again; all data comes back with
  the first sync.

## Phone

- Biometric lock (BiometricPrompt, strong biometrics or device PIN) on start and after a background timeout.
- The device token and the database key are wrapped by an AES-256-GCM key in the Android Keystore.
- Local data is in a SQLCipher-encrypted database; Android backup and device-to-device transfer are disabled.
- Progress photos stay in app-private storage; they are uploaded only when the owner chooses to discuss them, and
  reach Claude only if image analysis is allowed.
- The notification listener reads only allow-listed banking/payment apps; everything else is ignored.

## What goes to Claude

Only what a request needs (see docs/AGENT.md). Privacy settings control images, progress photos, archive
fragments and secret memories; memory types can be hidden from the agent; individual sources can be excluded or
marked "do not send". Imported texts, receipts, documents and web content are treated as data, never as
instructions.

## Backups

Daily encrypted dumps (AES-256, PBKDF2) verified after writing, kept separately from the database volume, with a
retention window and a documented restore check. Store `BACKUP_PASSPHRASE` off the server.

## Rotate after setup

Credentials shared during setup (server SSH key, GitHub token, Claude API key) should be rotated once the system
runs: generate new ones, update `deploy/.env` / GitHub secrets, revoke the old ones.
