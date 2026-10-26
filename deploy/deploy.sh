#!/bin/bash
# Update the backend on the server: pull, rebuild, restart (migrations run on api start).
#   cd ~/second-brain/deploy && ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
git -C .. pull --ff-only
docker compose build api embedder
docker compose up -d
sleep 10
docker compose ps --format "table {{.Service}}\t{{.Status}}"
curl -fsS http://127.0.0.1:8040/api/v1/system/health && echo
