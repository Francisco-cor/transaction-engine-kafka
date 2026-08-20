# ADR-004: JSON Schema y Schema Registry

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

El sistema debe demostrar evolución compatible de eventos y ser fácil de ejecutar localmente durante las primeras fases.

## Decisión

La primera versión usará JSON Schema con Confluent Schema Registry. Los eventos se versionan en el nombre lógico y en `schemaVersion`; los cambios aditivos deben ser opcionales o tener default. El fixture canónico se conserva en `docs/contracts`.

## Consecuencias

La serialización es legible durante troubleshooting y tiene una ruta clara a compatibilidad backward. Antes de producción se debe definir autenticación, subjects, compatibilidad y retención del registry.
