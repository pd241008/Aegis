#!/usr/bin/env bash
#
# Aegis end-to-end smoke check against the Docker Compose stack.
#
#   scripts/e2e.sh
#
# Builds the brain + sentinels, waits for the HTTP API, verifies a sentinel
# connects over gRPC, and confirms the retrieval/briefing/incident routes
# respond. Tears the stack down on exit.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HTTP_PORT="${AEGIS_HTTP_PORT:-9091}"
BRAIN_URL="http://127.0.0.1:${HTTP_PORT}"
MAX_WAIT="${MAX_WAIT:-120}"

compose() {
  docker compose -f "$ROOT/deployments/docker-compose.yml" "$@"
}

cleanup() {
  compose down --remove-orphans --volumes >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Building and starting the Aegis stack (brain + sentinels)..."
compose up -d --build

echo "==> Waiting for the brain HTTP API (up to ${MAX_WAIT}s)..."
deadline=$((SECONDS + MAX_WAIT))
until curl -fsS "${BRAIN_URL}/api/v1/incidents" >/dev/null 2>&1; do
  if ((SECONDS >= deadline)); then
    echo "ERROR: brain HTTP API did not come up within ${MAX_WAIT}s" >&2
    compose logs --tail 50 brain || true
    exit 1
  fi
  sleep 2
done
echo "    brain HTTP API is up"

echo "==> Checking sentinel connectivity to the brain..."
deadline=$((SECONDS + MAX_WAIT))
until compose logs sentinel-1 2>/dev/null | grep -q "Connected to brain"; do
  if ((SECONDS >= deadline)); then
    echo "ERROR: sentinel-1 never connected to the brain" >&2
    compose logs --tail 50 sentinel-1 || true
    exit 1
  fi
  sleep 2
done
echo "    sentinel-1 connected via gRPC"

echo "==> Verifying the retrieval pipeline..."
body="$(curl -fsS "${BRAIN_URL}/api/v1/retrieve?q=cpu%20usage")"
if [[ -z "$body" || "$body" == "[]" ]]; then
  echo "ERROR: retrieval returned no indexed telemetry" >&2
  compose logs --tail 50 brain || true
  exit 1
fi
echo "    retrieval OK ($(echo "$body" | wc -c) bytes of indexed telemetry)"

echo "==> Verifying briefings and incidents endpoints..."
curl -fsS "${BRAIN_URL}/api/v1/briefings" >/dev/null
curl -fsS "${BRAIN_URL}/api/v1/incidents" >/dev/null
echo "    OK"

echo
echo "Aegis E2E check passed."
