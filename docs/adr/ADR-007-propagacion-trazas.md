# ADR-007: Propagación de trazas en mensajería asíncrona

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-002, ADR-006, docs/operations/slo.md

## Contexto

Una transacción atraviesa `POST /transactions` → `outbox publisher` → Kafka → `ledger/fraud` →
`reconciliation` → (futuro) `notification`. Diagnosticar un `transaction_id` requiere correlacionar
logs y spans aunque el procesamiento sea asíncrono. El scaffold actual pasa `traceparent` y
`correlation_id` como headers Kafka manualmente (`TraceContext.java`), pero no hay instrumentación
OpenTelemetry ni sampling coherente.

## Decisión

1. **Formato:** W3C Trace Context (`traceparent`, `tracestate`) en headers HTTP y Kafka, además de
   `correlation_id` (UUID) y atributos de negocio `transaction_id`, `event_id`, `account_id`
   como baggage/log fields (sin PII).
2. **Instrumentación:** OpenTelemetry Java agent o `micrometer-tracing-bridge-otel` con
   `management.tracing.sampling.probability=1.0` en local y `0.1` + `always` para errores/DLT en demo.
   Biblioteca compartida `libs/observability` centraliza `TraceContext.resolve()` y helpers de MDC.
3. **Kafka:** `KafkaTemplate` interceptor crea span `producer` con `link` al span HTTP;
   `@KafkaListener` extrae `traceparent` y continúa trace (no sólo pasa string). Outbox persiste
   headers JSON con `traceparent` resuelto.
4. **Logs:** JSON estructurado (ECS) con `timestamp`, `service`, `environment`, `trace_id`, `span_id`,
   `transaction_id`, `event_id`, `account_id`, `outcome`, muestreo configurable.
5. **Métricas y traces:** Export OTLP a `jaeger:4317` / `tempo`, Prometheus para latencias,
   Grafana dashboards `API/Kafka/Ledger/Resilience/Chaos`.

## Alternativas descartadas

- **Solo correlation_id sin OTel:** insuficiente para latencias distribuidas y análisis de cuello de botella.
- **B3 propagation:** no estándar W3C, se descarta para interoperabilidad.

## Consecuencias

- Cada servicio debe propagar contexto aunque falle Redis/DB (fallback no rompe trace).
- Overhead mínimo en hot path; sampling evita explosión de costes.
- Búsqueda principal pasa a ser `transaction_id="abc123"` en Grafana/Loki/Jaeger, cumpliendo
  `IMPLEMENTATION_PLAN.md:291` y SLO `recovery ≤14s` medible con traces.
- Requiere Fase 2 completa: `libs/observability`, `management.tracing`, logback JSON, dashboards
  y `prometheus.yml` corregido a DNS compose.

## Validación

- `ContractValidationTest` y futuros tests de propagación deben verificar que `traceparent`
  persiste en `outbox_events.headers` y se restaura en consumer.
- `verify-invariants` no depende de trazas; observabilidad es orthonal a corrección financiera.
