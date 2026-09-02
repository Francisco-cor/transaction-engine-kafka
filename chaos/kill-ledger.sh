#!/usr/bin/env bash
set -euo pipefail
# kill-ledger.sh — chaos harness: randomly kills ledger-service container + pumba netem (F11)
# Usage: ./chaos/kill-ledger.sh [--interval 30] [--signal SIGKILL]
#        ./chaos/kill-ledger.sh netem --duration 15s (via pumba netem for DB)
INTERVAL=${1:-30}
SIGNAL=${2:-SIGKILL}
PROJECT=${COMPOSE_PROJECT:-transaction-engine-kafka}
if [ "$INTERVAL" = "netem" ]; then
  DURATION=${2:-15s}
  echo "[chaos] pumba netem delay 200ms jitter 50 for postgres ${DURATION}"
  # Requires pumba profile: docker compose -f chaos/docker-compose.chaos.yml --profile pumba run --rm pumba netem --duration "$DURATION" --tc-image gaiadocker/iproute2 delay --time 200 --jitter 50 re2:postgres
  docker compose -f chaos/docker-compose.chaos.yml --profile pumba run --rm pumba netem --duration "$DURATION" --tc-image gaiadocker/iproute2 delay --time 200 --jitter 50 re2:postgres || echo "pumba netem failed (need docker.sock)"
  # Also toxiproxy DB down 15s via API
  curl -s -X POST http://localhost:8474/proxies/postgres/toxics -H "Content-Type: application/json" -d '{"name":"db_down_15s","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":15000}}' || echo "toxiproxy DB down toxic failed"
  sleep 15
  curl -s -X DELETE http://localhost:8474/proxies/postgres/toxics/db_down_15s || echo "toxiproxy delete failed"
  exit 0
fi
echo "[chaos] killing ledger-service every ${INTERVAL}s with ${SIGNAL} + netem support"
while true; do
  sleep "$INTERVAL"
  CANDIDATE=$(docker ps --filter "name=${PROJECT}-ledger-service" --format "{{.ID}}" | head -1)
  if [ -n "$CANDIDATE" ]; then
    echo "[chaos] $(date -Is) killing $CANDIDATE signal $SIGNAL"
    docker kill --signal="$SIGNAL" "$CANDIDATE" || echo "kill failed"
    echo "[chaos] waiting 5s for recovery"
    sleep 5
    docker ps --filter "name=ledger-service" --format "{{.Names}} {{.Status}}"
  else
    echo "[chaos] no ledger container found"
  fi
done
