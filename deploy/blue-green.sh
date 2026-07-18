#!/usr/bin/env bash
# Zero-downtime blue-green deploy for api-gateway-service.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/docker-compose.yml}"
UPSTREAM_FILE="${UPSTREAM_FILE:-/etc/nginx/api-upstream.conf}"
STATE_FILE="${STATE_FILE:-/etc/masterdoc-api-gateway/active-slot}"
IMAGE="${GATEWAY_IMAGE:-api-gateway-service:local}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-60}"

active="$(cat "$STATE_FILE" 2>/dev/null || echo blue)"
if [[ "$active" == "blue" ]]; then
  new_slot="green"
  new_port=8084
  old_slot="blue"
else
  new_slot="blue"
  new_port=8083
  old_slot="green"
fi

echo "[blue-green] active=$active → deploying $new_slot on :$new_port image=$IMAGE"

export GATEWAY_IMAGE="$IMAGE"
docker compose -f "$COMPOSE_FILE" --profile "$new_slot" up -d "gateway-$new_slot"

deadline=$((SECONDS + HEALTH_TIMEOUT_SEC))
until curl -sf "http://127.0.0.1:${new_port}/health" >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "[blue-green] health check failed for :$new_port — rollback (upstream unchanged)" >&2
    docker compose -f "$COMPOSE_FILE" --profile "$new_slot" stop "gateway-$new_slot" || true
    exit 1
  fi
  sleep 1
done

tmp="$(mktemp)"
echo "server 127.0.0.1:${new_port};" >"$tmp"
sudo cp "$tmp" "$UPSTREAM_FILE"
rm -f "$tmp"
sudo nginx -t
sudo nginx -s reload

echo "$new_slot" | sudo tee "$STATE_FILE" >/dev/null

docker compose -f "$COMPOSE_FILE" --profile "$old_slot" stop "gateway-$old_slot" || true

echo "[blue-green] switched $old_slot → $new_slot (port $new_port)"
