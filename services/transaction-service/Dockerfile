FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY libs/event-contracts/pom.xml libs/event-contracts/pom.xml
COPY services/transaction-service/pom.xml services/transaction-service/pom.xml
COPY services/ledger-service/pom.xml services/ledger-service/pom.xml
RUN mvn -B -ntp -pl services/transaction-service -am dependency:go-offline
COPY libs/event-contracts/src libs/event-contracts/src
COPY services/transaction-service/src services/transaction-service/src
RUN mvn -B -ntp -pl services/transaction-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]