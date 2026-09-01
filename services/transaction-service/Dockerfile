# syntax=docker/dockerfile:1.6
# Fase 8-1: multi-stage optimizado con layer cache y soporte jlink
# Build stage cachea dependencias via --mount=type=cache y COPY --link
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Copia poms primero para maximizar cache de dependency:go-offline
COPY --link pom.xml .
COPY --link libs/event-contracts/pom.xml libs/event-contracts/pom.xml
COPY --link libs/observability/pom.xml libs/observability/pom.xml
COPY --link services/transaction-service/pom.xml services/transaction-service/pom.xml
COPY --link services/ledger-service/pom.xml services/ledger-service/pom.xml
COPY --link services/fraud-service/pom.xml services/fraud-service/pom.xml
COPY --link services/reconciliation-service/pom.xml services/reconciliation-service/pom.xml
COPY --link services/notification-service/pom.xml services/notification-service/pom.xml
COPY --link services/api-gateway/pom.xml services/api-gateway/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/transaction-service -am dependency:go-offline
COPY --link libs/event-contracts/src libs/event-contracts/src
COPY --link libs/observability/src libs/observability/src
COPY --link services/transaction-service/src services/transaction-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/transaction-service -am package -DskipTests
# Alternativa jlink: descomentar para generar runtime mínimo con jlink y usar gcr.io/distroless/java21-debian12:nonroot
# RUN jlink --add-modules java.base,java.logging,java.sql,java.naming,java.management,java.security.jgss,java.instrument,jdk.unsupported -o /jlink

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar app.jar
# USER no-root para hardening (F8-2 migrará a distroless nonroot:nonroot 65532)
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
