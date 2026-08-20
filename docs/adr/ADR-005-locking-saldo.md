# ADR-005: Lock pesimista por cuenta

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

Dos débitos concurrentes sobre la misma cuenta no pueden observar el mismo saldo y ambos comprometerse incorrectamente.

## Decisión

El ledger usará inicialmente `SELECT ... FOR UPDATE` sobre la fila de `accounts`, con timeout de lock y métricas de espera. El orden por `accountId` en Kafka ayuda, pero no sustituye la protección en PostgreSQL.

## Consecuencias

La semántica es explícita y fácil de probar, pero una cuenta caliente puede limitar throughput. Una migración a optimistic locking requiere benchmark y un ADR sustituto.
