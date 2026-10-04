#!/usr/bin/env bash
set -euo pipefail

TARGET="${DEPLOY_TARGET:-josemurga@164.68.108.97}"
BASE="${DEPLOY_BASE:-/opt/traza}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPOS=(expedientes-api expedientes-web)

for repo in "${REPOS[@]}"; do
  if [ -n "$(git -C "$RAIZ/$repo" status --porcelain)" ]; then
    echo "$repo tiene cambios sin commit: no se despliega nada." >&2
    exit 1
  fi
  echo "$repo $(git -C "$RAIZ/$repo" log -1 --format='%h %s')"
done

REVISION="$(git -C "$RAIZ/expedientes-api" rev-parse --short HEAD)/$(git -C "$RAIZ/expedientes-web" rev-parse --short HEAD)"
PAQUETE="$(mktemp -d)"
trap 'rm -rf "$PAQUETE"' EXIT

for repo in "${REPOS[@]}"; do
  mkdir -p "$PAQUETE/$repo"
  git -C "$RAIZ/$repo" -c core.autocrlf=false archive HEAD | tar -x -C "$PAQUETE/$repo"
done

tar -c -C "$PAQUETE" "${REPOS[@]}" | ssh "$TARGET" \
  "set -e; rm -rf $BASE/src.new; mkdir -p $BASE/src.new; tar -x -C $BASE/src.new; BASE=$BASE REVISION=$REVISION bash $BASE/src.new/expedientes-api/deploy/remoto.sh"
