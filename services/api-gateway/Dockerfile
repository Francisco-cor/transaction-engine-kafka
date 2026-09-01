FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY libs/event-contracts/pom.xml libs/event-contracts/pom.xml
COPY libs/observability/pom.xml libs/observability/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/transaction-service/pom.xml services/transaction-service/pom.xml
COPY services/ledger-service/pom.xml services/ledger-service/pom.xml
COPY services/fraud-service/pom.xml services/fraud-service/pom.xml
COPY services/reconciliation-service/pom.xml services/reconciliation-service/pom.xml
RUN mvn -B -ntp -pl services/api-gateway -am dependency:go-offline
COPY libs/event-contracts/src libs/event-contracts/src
COPY libs/observability/src libs/observability/src
COPY services/api-gateway/src services/api-gateway/src
RUN mvn -B -ntp -pl services/api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
