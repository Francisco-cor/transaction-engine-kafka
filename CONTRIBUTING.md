# Contribuir

## Flujo local

1. Crear una rama con prefijo `feature/`, `fix/`, `docs/`, `test/` o `chore/`.
2. Mantener los cambios pequeños y actualizar la documentación/ADR correspondiente.
3. Ejecutar `pwsh -File .\scripts\Invoke-Project.ps1 -Command quality` antes de abrir un PR.
4. No incluir secretos, datos de producción ni PII en fixtures, logs o Compose.

## Convención de commits

Se usa Conventional Commits: `type(scope): summary`, por ejemplo `feat(ledger): add inbox deduplication`. Tipos permitidos: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore` y `perf`.

## Reglas técnicas

- Java 21 y Maven son el toolchain oficial.
- Los importes usan `BigDecimal`; no se aceptan `double` ni `float` para dinero.
- Los cambios de esquema requieren migración versionada.
- Las decisiones que afecten garantías, contratos, IDs, locking o infraestructura se registran en `docs/adr`.
- Una tarea no se marca completa sin código, prueba y documentación verificable.
