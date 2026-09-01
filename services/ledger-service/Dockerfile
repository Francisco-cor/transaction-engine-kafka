# syntax=docker/dockerfile:1.6
# Fase 8-1: multi-stage optimizado con layer cache y soporte jlink
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY --link pom.xml .
COPY --link libs/event-contracts/pom.xml libs/event-contracts/pom.xml
COPY --link libs/observability/pom.xml libs/observability/pom.xml
COPY --link services/transaction-service/pom.xml services/transaction-service/pom.xml
COPY --link services/ledger-service/pom.xml services/ledger-service/pom.xml
COPY --link services/fraud-service/pom.xml services/fraud-service/pom.xml
COPY --link services/reconciliation-service/pom.xml services/reconciliation-service/pom.xml
COPY --link services/notification-service/pom.xml services/notification-service/pom.xml
COPY --link services/api-gateway/pom.xml services/api-gateway/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/ledger-service -am dependency:go-offline
COPY --link libs/event-contracts/src libs/event-contracts/src
COPY --link libs/observability/src libs/observability/src
COPY --link services/ledger-service/src services/ledger-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/ledger-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/ledger-service/target/ledger-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
