# syntax=docker/dockerfile:1.6
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
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
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/reconciliation-service -am dependency:go-offline
COPY --link libs/event-contracts/src libs/event-contracts/src
COPY --link libs/observability/src libs/observability/src
COPY --link services/reconciliation-service/src services/reconciliation-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/reconciliation-service -am package -DskipTests

FROM debian:bookworm-slim@sha256:88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171 AS tini-stage
RUN apt-get update && apt-get install -y --no-install-recommends tini && cp /usr/bin/tini /tini && chmod +x /tini

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d
WORKDIR /app
COPY --from=tini-stage /tini /usr/bin/tini
COPY --from=build /workspace/services/reconciliation-service/target/reconciliation-service-0.1.0-SNAPSHOT.jar app.jar
USER nonroot:nonroot
EXPOSE 8084
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=15s --timeout=3s --retries=3 --start-period=40s CMD wget -qO- http://localhost:8084/actuator/health || exit 1
