# ADR-003: UUID como identificador externo

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

Los eventos necesitan IDs únicos globalmente y los clientes necesitan un `transactionId` que no revele secuencia ni dependa de una base central.

## Decisión

Usaremos UUID para `transactionId` y `eventId` en la primera versión. Se representarán como UUID en PostgreSQL y como strings UUID en JSON. No se mezclará ULID sin un ADR posterior.

## Consecuencias

La generación es simple y portable, aunque los UUID aleatorios pueden ser menos amigables para índices que un identificador ordenable. La decisión puede revisarse antes de una carga sostenida.
