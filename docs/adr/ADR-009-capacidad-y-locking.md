# ADR-009: Capacidad y tradeoffs de locking

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-005 locking pesimista, docs/operations/capacity.md, Fase 7

## Contexto

Con `KAFKA_PARTITIONS=6` y `Hikari max 20`, el cuello de botella pasa a ser el lock pesimista
`SELECT ... FOR UPDATE` por `accountId`. Una cuenta caliente (`hot-account-001` Zipf 50% en k6)
serializa todas las transacciones de esa cuenta. Para 50 rps, lock wait p95 debe <100ms,
sino throughput cae. Alternativa es optimistic locking (`version` column + retry) que aumenta
throughput para cuentas frías pero genera retries en caliente.

## Decisión

1. **Mantener pesimista por defecto** con `lock_timeout 3s` y métrica `ledger_lock_wait_seconds`.
   - Ventaja: semántica explícita, sin retry storm, fácil de probar (`LedgerConcurrentBalanceIntegrationTest`).
   - Desventaja: hot account limita a ~1/lock_wait throughput (~10-20 TPS por cuenta caliente con 50ms wait).
2. **Optimistic como experimento opt-in:** añadir `version` check `UPDATE accounts SET balance=..., version=version+1 WHERE version=:old` y retry acotado (max 3, backoff 10ms). Medir con `LoadInvariantsTest` y k6.
3. **Capacidad:**
   - Particiones 6 > consumers 3, permite escalar ledger a 6 pods (uno por partición).
   - Pool 20 > particiones * consumers (6*2=12) + margen, evita `pool pending`.
   - Compression `zstd` reduce red y batch.
4. **Mitigación hot account:** si `ledger_lock_wait_seconds p95 >100ms` sostenido, evaluar sharding por `accountId` hash a subtablas o `SELECT ... FOR UPDATE NOWAIT` con retry jitter.

## Métricas de decisión

| Métrica | Umbral | Acción |
|---|---|---|
| `ledger_lock_wait_seconds p95` | >100ms | Revisar lock_timeout / considerar optimistic |
| `hikaricp_connections_pending` | >0 | Aumentar pool o reducir `maxConcurrentCalls` bulkhead |
| `outbox_pending_events` | >100 for 60s | Revisar publisher lease/backoff |
| `k6 p95` | >500ms | Revisar lock + DB latency |

## Consecuencias

- Demo local con `hot-account-001` saturará pero no romperá invariantes; k6 reportará lock wait.
- Futuro ADR puede promover optimistic si benchmark 10k muestra p95 <200ms y throughput +30%.
- `docs/operations/capacity.md` mantiene guía de tuning y reproduce `verify-invariants` tras cada cambio.

## Validación

- `perf(ledger)` lock_timeout + `LedgerMetrics` lock_timer.
- `LoadInvariantsTest.balanceFinalEqualsSumOfLedger` y `LedgerConcurrentBalanceIntegrationTest.hotAccount...` verdes.
- `k6-transactions.js` reporte `p95 <500ms` con 50 rps en laptop documentado (no prod).
