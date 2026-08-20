# ADR-001: Maven y estructura del monorepo

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

El repositorio empieza greenfield y necesita compilar de forma uniforme en local y CI, con varios servicios y bibliotecas pequeñas.

## Decisión

Usaremos Maven con Java 21 y un POM agregador. Los módulos de negocio viven bajo `services/` y los contratos/utilidades pequeñas bajo `libs/`. Spring Boot será la base de los servicios ejecutables.

## Consecuencias

Existe un único comando de calidad (`mvn verify`) y las versiones de plugins se centralizan. Un servicio no debe esconder lógica de negocio compartida en una biblioteca común.
