# SLOs, límites y retención de demo

Estos objetivos son para el entorno local/portfolio y no constituyen un SLO productivo.

| Señal | Objetivo inicial | Límite de alerta |
|---|---:|---:|
| Disponibilidad de `POST /transactions` | 99% durante una ejecución de demo | < 99% |
| Latencia p95 de aceptación | ≤ 500 ms sin dependencias caídas | > 1 s |
| Recuperación después de fallo controlado | ≤ 14 s en hardware documentado | > 14 s |
| Backlog de outbox | 0 al estabilizar | Crece durante 60 s |
| Mensajes en DLT | 0 para payloads válidos | Cualquier crecimiento sostenido |
| Cobertura de líneas del servicio | ≥ 50% en fase 0 | < 50% |

## SLOs PLG con exemplars (F2)

| Señal | SLI | Objetivo | Fuente | Alerta |
|---|---|---|---|---|
| Latencia p95 | `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | ≤500ms | `job:http_requests:rate5m` | `HighApiLatency` |
| Lock wait p95 | `job:ledger_lock_wait:p95` | <100ms | `recording-rules.yml` | `DbPoolExhausted` |
| Trace sampling | `tail_sampling` 10% + 100% errors | >99% errors sampled | `collector-config.yml` | `OtelCollectorDown` |
| Log correlation | `Loki` `transaction_id`→`trace_id` | 100% logs con trace_id | `logback` MDC | `LokiIngestionErrors` |
| Profiling | `pyroscope` lock_wait flame | presente | `PYROSCOPE_SERVER_ADDRESS` | `PyroscopeProfilingDown` |

Exemplars: `prometheus.yml:44` `enable_exemplar: true` + `otel-collector` `spanmetrics.exemplars.enabled` → Grafana click en `ledger_lock_wait` punto lleva a Tempo `trace_id`.

## Límites de carga iniciales

- 10,000 transacciones es el benchmark de referencia de fases posteriores.
- La primera demo local limita el productor a 50 requests por segundo y 20 conexiones concurrentes.
- El Compose local usa un broker, un PostgreSQL y tres particiones lógicas por topic; no se debe interpretar como capacidad de producción.
- El saldo y los importes se almacenan como `NUMERIC(19,4)`.

## Retención de datos de demo

- Kafka: 7 días (`604800000` ms) para topics de negocio y 14 días para DLT.
- PostgreSQL: persistencia en volumen nombrado; la limpieza se ejecuta solo con `clean-data -RemoveData`.
- Redis: volumen local y TTL en cada uso de cache; nunca es fuente de verdad financiera.
- Traces: almacenamiento en memoria de Jaeger local, sin promesa de retención tras reinicio.
- Prometheus: 7 días en volumen local; Grafana conserva configuración, no datos financieros.

Los límites y credenciales por defecto están centralizados en `infra/docker-compose/.env.example` y deben sobrescribirse por variables de entorno en entornos no locales.
