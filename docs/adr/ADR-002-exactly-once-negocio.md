# ADR-002: Exactly-once a nivel de negocio

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

Kafka entrega al menos una vez y puede redeliverar un mensaje después de un commit local. Exactly-once absoluto de toda la infraestructura no es una promesa realista.

## Decisión

La garantía será exactly-once a nivel de negocio mediante una transacción local, transactional outbox en productores, inbox por consumidor y constraints únicos en los efectos financieros. El ACK de Kafka ocurrirá después del commit de la base local.

## Consecuencias

Puede existir publicación duplicada a nivel de transporte, pero el efecto financiero será idempotente y auditable. Cada consumidor debe definir su propia clave de deduplicación y tratamiento de DLT.
