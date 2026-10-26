# Deployment

The backend is one Docker Compose project (`deploy/docker-compose.yml`) that can share a VPS with other
projects: only the API is published, on `127.0.0.1:8040`; nginx terminates TLS in front of it.

| Service | Image | Role | Memory limit |
|---|---|---|---|
| `db` | `pgvector/pgvector:pg16` | PostgreSQL 16 + pgvector | 512 MB |
| `redis` | `redis:7-alpine` | arq queue, rate limits, small caches | 128 MB |
| `embedder` | `second-brain-embedder` | local multilingual embeddings (MiniLM, 384 dims) | 900 MB |
| `api` | `second-brain-app` | FastAPI; runs migrations and `app.cli init` on start | 450 MB |
| `worker` | `second-brain-app` | arq worker: agent jobs, pushes, events, embeddings | 450 MB |
| `scheduler` | `second-brain-app` | dispatches due jobs, system recurring jobs, sweeps | 200 MB |
| `backup` | `pgvector/pgvector:pg16` | daily encrypted `pg_dump` with verification | 128 MB |

The network uses its own subnet `172.31.240.0/24`; change it in the compose file if it collides with something
on your host.

## First install

```bash
git clone https://github.com/IvanZagulin/second-brain.git ~/second-brain
cd ~/second-brain/deploy
cp .env.example .env          # fill in secrets, see docs/CONFIGURATION.md
mkdir -p secrets && chmod 700 secrets
# put fcm-service-account.json and google-services.json into deploy/secrets/
sudo chown -R 10001:10001 secrets   # containers run as uid 10001
docker compose build
docker compose up -d
docker compose exec api python -m app.cli seed /secrets/seed.json   # optional personal starting data
docker compose exec api python -m app.cli pair                      # one-time code for the phone
```

`seed.json` is never committed; see `app/cli.py` for its shape (user, schedule, finance, fitness,
accounts, debts, goals, workout plans, starting memories).

## HTTPS with an existing nginx

The reference server routes port 443 through an nginx `stream` SNI map (VPN domains → xray, everything else →
`127.0.0.1:4443` with PROXY protocol). The site file `deploy/nginx/brain.conf` follows that layout. Steps:

1. DNS: `A brain.example.com → <server IP>` (no CDN proxying: SSE and large uploads need a direct connection).
2. Install only the port-80 block first, `sudo nginx -t && sudo systemctl reload nginx`.
3. `sudo certbot certonly --webroot -w /var/www/certbot -d brain.example.com`
4. Install the full `brain.conf`, `sudo nginx -t && sudo systemctl reload nginx`.

Without such a router, change `listen 127.0.0.1:4443 ssl proxy_protocol;` to `listen 443 ssl;`.

Do not enable `http2` in that server block on nginx 1.24: it is a property of the shared listen socket and would
change every other site behind it.

## Updating

```bash
cd ~/second-brain/deploy && ./deploy.sh
```

pulls, rebuilds `api`/`embedder`, restarts; migrations run on `api` start.

## Outbound access to the Claude API

If the VPS is in a region the API does not serve, set `ANTHROPIC_PROXY=http://user:pass@host:port` in `.env`.
Only Claude API calls (and optionally FCM, `FCM_USE_PROXY=true`) use it.

## Backups and restore

- Daily at `BACKUP_AT` (default 04:00, `TZ`), the `backup` service writes
  `$BACKUP_DIR/brain-YYYYmmdd-HHMM.dump.enc` (custom-format dump, AES-256-CBC with PBKDF2) and a `.sha256`.
- Each backup is decrypted and listed with `pg_restore --list` before it is kept.
- Retention: `BACKUP_KEEP_DAYS` (default 30, `0` keeps everything). Only `brain-*.dump.enc*` files are touched.
- Keep `BACKUP_PASSPHRASE` outside the server too — without it backups cannot be decrypted.

Manual backup: `docker compose run --rm backup now`.

Restore check (into a scratch database, the live one is untouched):

```bash
cd ~/second-brain/deploy && ./scripts/restore.sh /var/backups/second-brain/brain-20261027-0400.dump.enc
```

Real restore: add `--live` (stops api/worker/scheduler, keeps the previous database as `<name>_old`).
Run the check monthly. Copy backups off the server (e.g. `scp`) for disaster recovery.

## Health and logs

- `curl -s http://127.0.0.1:8040/api/v1/system/health`
- `docker compose ps`, `docker compose logs -f api worker scheduler`
- Logs are JSON, rotated (10 MB × 3 per container) and never include message texts, bank notification texts or
  keys unless `DEBUG_LOG_BODIES=true`.
- Phone-side logs uploaded from Diagnostics land in the `appdata` volume under `client-logs/`.
