#!/bin/bash
# Daily encrypted PostgreSQL backup, verified after writing.
#   file:   /backups/brain-YYYYmmdd-HHMM.dump.enc   (pg_dump -Fc, AES-256 via openssl, PBKDF2)
#   check:  decrypt → pg_restore --list must succeed; sha256 stored next to the file
#   retention: BACKUP_KEEP_DAYS (0 = keep forever); only files matching brain-*.dump.enc are touched
# Run once by hand:  docker compose run --rm backup now
set -u

backup_once() {
  local stamp file
  stamp="$(date +%Y%m%d-%H%M)"
  file="/backups/brain-${stamp}.dump.enc"
  if ! pg_dump -Fc --no-owner | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
        -pass env:BACKUP_PASSPHRASE -out "${file}.tmp"; then
    echo "$(date -Is) backup FAILED (dump)"; rm -f "${file}.tmp"; return 1
  fi
  if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_PASSPHRASE -in "${file}.tmp" \
        | pg_restore --list > /dev/null; then
    echo "$(date -Is) backup FAILED (verify)"; rm -f "${file}.tmp"; return 1
  fi
  mv "${file}.tmp" "${file}"
  sha256sum "${file}" > "${file}.sha256"
  echo "$(date -Is) backup ok ${file} $(du -h "${file}" | cut -f1)"
  if [ "${BACKUP_KEEP_DAYS:-30}" -gt 0 ]; then
    find /backups -maxdepth 1 -name 'brain-*.dump.enc*' -mtime +"${BACKUP_KEEP_DAYS}" -print -delete
  fi
}

if [ -z "${BACKUP_PASSPHRASE:-}" ]; then
  echo "BACKUP_PASSPHRASE is not set"; exit 1
fi
command -v openssl > /dev/null || { apt-get update -qq && apt-get install -y -qq openssl > /dev/null; }

if [ "${1:-}" = "now" ]; then
  backup_once; exit $?
fi

echo "backup service: daily at ${BACKUP_AT} (${TZ})"
while true; do
  now=$(date +%s)
  target=$(date -d "today ${BACKUP_AT}" +%s)
  [ "$target" -le "$now" ] && target=$(date -d "tomorrow ${BACKUP_AT}" +%s)
  sleep $((target - now))
  backup_once || true
  sleep 60
done
