# ADR-014: PITR, CDC y GDPR

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-002 exactly-once, ADR-009 capacidad, Fase 7, docs/runbooks/backup-restore.md
- Superseeds: RDS retention 1d → 7d para PITR

## Contexto

`v0.5.1` tenía `PostgreSQL 16.4 single` sin `wal_level logical`, retención 1d en dev, reconciliación solo `@Scheduled 2s poll DB`, y PII `customerNote` sin derecho al olvido. Para prod necesitamos RPO 5 min RTO 15 min, reconciliación sin lag por poll (CDC), y GDPR tokenización+erasure sin romper ledger append-only.

## Decisión

1. **PITR 7d + WAL-G S3:**
   - `infra/terraform/modules/rds/main.tf:48` `backup_retention_period 7` (var default 7) + `infra/terraform/modules/rds/backup.tf:1` `aws_backup_plan continuous-pitr cron(0/5) delete_after 7` + `aws_s3_bucket wal_archive` SSE-KMS versioning → RPO 5 min.
   - `infra/terraform/envs/dev/main.tf:77` `1→7`, `demo` `1→7`, staging ya 7, `infra/docker-compose/docker-compose.yml:75` `postgres command wal_level=logical max_replication_slots=5 max_wal_senders=5` para local CDC y WAL.
   - `docs/runbooks/backup-restore.md:1` drill trimestral `restore-db-instance-to-point-in-time --use-latest-restorable-time` + `wait available` + `verify-invariants.sql` RTO <15 min Q1-Q4.

2. **CDC Debezium pgoutput:**
   - `infra/postgres/migrations/V10__cdc_replica_identity.sql:1` `ALTER ROLE transaction_migrator REPLICATION`, `REPLICA IDENTITY FULL` para 8 tablas + `PUBLICATION debezium_publication FOR TABLE outbox_events,transactions,ledger_entries,accounts`.
   - `infra/docker-compose/docker-compose.cdc.yml:1` overlay `debezium:2.5.4.Final` `BOOTSTRAP_SERVERS kafka:29092` `CONFIG_STORAGE_TOPIC debezium_* REPLICATION_FACTOR 1` + `debezium-init curlimages/curl` que registra connector `txengine-outbox` con `PostgresConnector pgoutput slot debezium_txengine publication debezium_publication` `outbox.EventRouter` `route.topic transactions.cdc`.
   - `services/reconciliation-service/src/main/resources/application.yml:50` `reconciliation.cdc.enabled false` default + `reconciliation.cdc.topic transactions.cdc` + `spring.kafka bootstrap-servers, group-id reconciliation-service, AckMode MANUAL_IMMEDIATE`.
   - `services/reconciliation-service/src/main/java/com/example/transactionengine/reconciliation/messaging/CdcReconciliationListener.java:11` `@ConditionalOnProperty reconciliation.cdc.enabled=true` `@KafkaListener transactions.cdc` extrae `aggregate_id/transaction_id` via `ObjectMapper` + regex UUID, llama `reconciliation.triggerReconciliation(UUID)`.
   - `ReconciliationApplicationService.java:51` añade `triggerReconciliation(UUID)` `@Transactional` que reutiliza `reconcileOne`, manteniendo `@Scheduled poll 2s` como fallback (sin CDC sigue funcionando).
   - `ReconciliationKafkaConfiguration.java:11` `@EnableKafka` `ConcurrentKafkaListenerContainerFactory` manual.

3. **GDPR tokenización + erasure:**
   - `libs/security/VaultTransitClient.java:41` ya tokeniza `customerNote vault:` vs `plain` (F3). F7 añade `services/transaction-service/src/main/java/com/example/transactionengine/transaction/api/GdprController.java:11` `DELETE /customers/{accountId} params=!local` `@PreAuthorize SCOPE_admin:gdpr|SCOPE_gdpr:write` + fallback `params=local` para dx sin JWT, ambos validan `TenantOwnershipValidator`.
   - `GdprService.java:11` `@Transactional` `erase(accountId, requestedBy, reason)` → `GdprRepository.recordErasure`, `scrubCustomerNote` `UPDATE outbox_events SET payload = payload - 'customerNote' - 'customerNoteVault' WHERE payload->>'accountId'=:id`, `anonymizeTransactions` `UPDATE transactions SET idempotency_scope='erased-' || :id`, audit via `AuditLogger.logReplay`.
   - `GdprRepository.java:11` + `infra/postgres/migrations/V11__gdpr_and_partitioning.sql:5` `gdpr_erasure_requests request_id PK account_id UNIQUE requested_by reason`.
   - `SecurityConfig.java:37` nuevo `requestMatchers("/customers/**").hasAnyAuthority("SCOPE_admin:gdpr","SCOPE_gdpr:write")`.

4. **Partitioning por rango:**
   - `V11__gdpr_and_partitioning.sql:18` crea `ledger_entries_partitioned PARTITION BY RANGE (created_at)` con `INCLUDING ALL` + 6 particiones mensuales `ledger_entries_p_YYYY_MM` + `transactions_partitioned` similar; `GRANT SELECT,INSERT TO "${appUser}"`.
   - `BRIN` V9 sigue para scans temporales; particiones permiten `DETACH/TRUNCATE` mensual sin `DELETE` y `REFRESH account_statement_mv`.
   - Doc `docs/operations/capacity.md` actualizado con PITR y CDC.

## Alternativas

- **Solo RDS Automated Backup sin S3 WAL-G:** RPO depende de backup_window 3-4h, no 5 min — descartado.
- **Poll CDC via logical decoding directo en app:** requiere lib `pgjdbc` replication API, complejo y bloqueante — Debezium desacopla.
- **Hard delete ledger para GDPR:** rompería invariante `COMMITTED==ledger` y auditabilidad — descartado, scrub PII pero ledger queda.
- **Partitioning por hash accountId:** útil para sharding F6, pero para retención temporal rango es más operable.

## Consecuencias

- Debezium requiere `wal_level logical` → +30% WAL volume local, ok; en RDS `rds.logical_replication=1` vía `aws_db_parameter_group` (pendiente F8 helm param).
- CDC topic `transactions.cdc` con `EventRouter` filtra solo `outbox_events`; si outbox backlogged, CDC lag igual, pero reconciliación se dispara en ms tras `markPublished` + Debezium commit.
- GDPR scrub deja `ledger_entries` intacto, solo `outbox_events` PII borrado; `Vault` ciphertext sigue en `transaction` si vault enabled pero sin key no decodifica.
- Particiones mensuales vacías no afectan `SELECT` en `ledger_entries` actual; migración zero-downtime a particionado requeriría `pg_partman` futuro — documentado como `partitioned` shadow table.

## Validación

- `psql -c "SELECT pubname FROM pg_publication"` → `debezium_publication` tras `V10`.
- `docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.cdc.yml config --quiet` verde, `curl http://localhost:8088/connectors/txengine-outbox` 200 tras `debezium-init`.
- `RECONCILIATION_CDC_ENABLED=true docker compose up -d` → `POST /transactions` → log `CDC reconcile trigger` + `reconciliation_results status MATCHED` sin esperar `2000ms poll`.
- `curl -X DELETE http://localhost:8080/customers/demo-acc-001?local -H "X-Tenant-Id: demo"` → `{"accountId":"demo-acc-001","scrubbedOutbox":1}` + `SELECT * FROM gdpr_erasure_requests` 1 fila + `SELECT payload FROM outbox_events WHERE payload->>'accountId'='demo-acc-001'` sin `customerNote`.
- `terraform -chdir=infra/terraform/envs/dev plan | grep backup_retention` → 7.
