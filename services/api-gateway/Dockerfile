# syntax=docker/dockerfile:1.6
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY --link pom.xml .
COPY --link libs/event-contracts/pom.xml libs/event-contracts/pom.xml
COPY --link libs/observability/pom.xml libs/observability/pom.xml
COPY --link services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY --link services/transaction-service/pom.xml services/transaction-service/pom.xml
COPY --link services/ledger-service/pom.xml services/ledger-service/pom.xml
COPY --link services/fraud-service/pom.xml services/fraud-service/pom.xml
COPY --link services/reconciliation-service/pom.xml services/reconciliation-service/pom.xml
COPY --link services/notification-service/pom.xml services/notification-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/api-gateway -am dependency:go-offline
COPY --link libs/event-contracts/src libs/event-contracts/src
COPY --link libs/observability/src libs/observability/src
COPY --link services/api-gateway/src services/api-gateway/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/api-gateway -am package -DskipTests

FROM debian:bookworm-slim AS tini-stage
RUN apt-get update && apt-get install -y --no-install-recommends tini && cp /usr/bin/tini /tini && chmod +x /tini

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=tini-stage /tini /usr/bin/tini
COPY --from=build /workspace/services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER nonroot:nonroot
EXPOSE 8085
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=15s --timeout=3s --retries=3 --start-period=40s CMD wget -qO- http://localhost:8085/actuator/health || exit 1
