#!/bin/bash
# Restore a backup into a *separate* scratch database to verify it (default), or into the live
# database with --live (stops api/worker/scheduler first). Run from deploy/:
#   ./scripts/restore.sh /var/backups/second-brain/brain-20261027-0400.dump.enc          # test restore
#   ./scripts/restore.sh /var/backups/second-brain/brain-20261027-0400.dump.enc --live   # real restore
set -euo pipefail
file="${1:?path to .dump.enc}"
mode="${2:-test}"
set -a; source .env; set +a
sha256sum -c "${file}.sha256"

if [ "$mode" = "--live" ]; then
  docker compose stop api worker scheduler
  target="${POSTGRES_DB}"
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS ${target}_old"
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE ${target} RENAME TO ${target}_old"
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE ${target}"
else
  target="restore_check"
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS ${target}"
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE ${target}"
fi

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in "$file" \
  | docker compose exec -T db pg_restore -U "$POSTGRES_USER" -d "$target" --no-owner
docker compose exec -T db psql -U "$POSTGRES_USER" -d "$target" -c \
  "SELECT (SELECT count(*) FROM memories) AS memories, (SELECT count(*) FROM transactions) AS transactions, (SELECT count(*) FROM messages) AS messages"

if [ "$mode" = "--live" ]; then
  docker compose up -d api worker scheduler
  echo "restored into ${target}; previous database kept as ${target}_old"
else
  docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE ${target}"
  echo "test restore OK"
fi
