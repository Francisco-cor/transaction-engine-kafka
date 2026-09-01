#!/usr/bin/env bash
set -euo pipefail
# kill-ledger.sh — chaos harness: randomly kills ledger-service container
# Usage: ./chaos/kill-ledger.sh [--interval 30] [--signal SIGKILL]
INTERVAL=${1:-30}
SIGNAL=${2:-SIGKILL}
PROJECT=${COMPOSE_PROJECT:-transaction-engine-kafka}
echo "[chaos] killing ledger-service every ${INTERVAL}s with ${SIGNAL}"
while true; do
  sleep "$INTERVAL"
  CANDIDATE=$(docker ps --filter "name=${PROJECT}-ledger-service" --format "{{.ID}}" | head -1)
  if [ -n "$CANDIDATE" ]; then
    echo "[chaos] $(date -Is) killing $CANDIDATE signal $SIGNAL"
    docker kill --signal="$SIGNAL" "$CANDIDATE" || echo "kill failed"
    # Verify DLT still drains
    echo "[chaos] waiting 5s for recovery"
    sleep 5
    docker ps --filter "name=ledger-service" --format "{{.Names}} {{.Status}}"
  else
    echo "[chaos] no ledger container found"
  fi
done
