#!/usr/bin/env bash
set -Eeuo pipefail

bootstrap_server="${KAFKA_BOOTSTRAP_SERVER:-kafka:29092}"
topic_retention_ms="${TOPIC_RETENTION_MS:-604800000}"
dlt_retention_ms="${DLT_RETENTION_MS:-1209600000}"
partitions="${KAFKA_PARTITIONS:-6}"

create_topic() {
  local topic="$1"
  local partitions="$2"
  local retention_ms="$3"

  kafka-topics --bootstrap-server "$bootstrap_server" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1 \
    --config cleanup.policy=delete \
    --config retention.ms="$retention_ms" \
    --config min.insync.replicas=1

  kafka-configs --bootstrap-server "$bootstrap_server" \
    --entity-type topics --entity-name "$topic" --alter \
    --add-config "retention.ms=$retention_ms,cleanup.policy=delete,min.insync.replicas=1"
}

create_topic "transactions.created.v1" "$partitions" "$topic_retention_ms"
create_topic "transactions.committed.v1" "$partitions" "$topic_retention_ms"
create_topic "transactions.created.v1.ledger-service.retry" "$partitions" "$topic_retention_ms"
create_topic "transactions.created.v1.ledger-service.DLT" "$partitions" "$dlt_retention_ms"
create_topic "transactions.fraud-decisions.v1" "$partitions" "$topic_retention_ms"
create_topic "transactions.created.v1.fraud-service.DLT" "$partitions" "$dlt_retention_ms"
create_topic "transactions.committed.v1.notification-service.DLT" "$partitions" "$dlt_retention_ms"

echo 'Topics creados/verificados:'
kafka-topics --bootstrap-server "$bootstrap_server" --list | sort
