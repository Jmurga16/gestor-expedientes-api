#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
BASE="$(cd ../../.. && pwd)"
compose() { docker compose --env-file "$BASE/.env" "$@"; }

date '+%F %T'
compose rm -sf almacen
docker volume rm traza_almacen-data
compose up -d almacen
compose run --rm -T --no-deps -e SEED_RESET=true api --spring.profiles.active=seed
