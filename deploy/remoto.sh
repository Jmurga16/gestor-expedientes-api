#!/usr/bin/env bash
set -euo pipefail
: "${BASE:?}" "${REVISION:?}"
EDGE_SITES="${EDGE_SITES:-/opt/edge/sites}"
EDGE_CADDY="${EDGE_CADDY:-edge-caddy-1}"

rm -rf "$BASE/src.old"
if [ -d "$BASE/src" ]; then mv "$BASE/src" "$BASE/src.old"; fi
mv "$BASE/src.new" "$BASE/src"
cd "$BASE/src/expedientes-api/deploy"

SITE_HOST="$(sed -n 's/^SITE_HOST=//p' "$BASE/.env")"
compose() { docker compose --env-file "$BASE/.env" "$@"; }
mongo() { compose exec -T db sh -c 'mongosh -u traza -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --quiet --eval "$1" db_expedientes' sh "$1"; }

compose up -d --build --remove-orphans --wait
docker image prune -f --filter label=com.docker.compose.project=traza >/dev/null
if [ "${RECORTAR_CACHE:-1}" = 1 ]; then
  docker builder prune -f --reserved-space 1gb >/dev/null
fi

if [ "$(mongo 'db.users.countDocuments()')" = 0 ]; then
  echo "Base sin usuarios: se siembra la demo."
  compose run --rm -T --no-deps api --spring.profiles.active=seed
fi

# Hora del servidor (Europa central): domingo a las 10:00, las 03:00 de Lima.
TAREA="0 10 * * 0 bash $BASE/src/expedientes-api/deploy/sembrar.sh >> $BASE/sembrar.log 2>&1"
( crontab -l 2>/dev/null | grep -v "$BASE/src/expedientes-api/deploy/sembrar.sh" || true; echo "$TAREA" ) | crontab -

if [ -d "$EDGE_SITES" ]; then
  printf '%s {\n\theader X-Robots-Tag "noindex, nofollow"\n\treverse_proxy traza-web:80\n}\n' \
    "$SITE_HOST" > "$EDGE_SITES/traza.caddy"
  docker exec "$EDGE_CADDY" caddy reload --config /etc/caddy/Caddyfile
fi

for _ in $(seq 60); do
  respuesta="$(compose exec -T web wget -S -O /dev/null http://127.0.0.1/api/tipo-demanda 2>&1 || true)"
  if [[ "$respuesta" == *"HTTP/1.1 40"[13]* ]]; then
    echo "Listo: https://$SITE_HOST con la revisión $REVISION"
    exit 0
  fi
  sleep 3
done
echo "La API no responde tras tres minutos: docker compose logs api" >&2
exit 1
