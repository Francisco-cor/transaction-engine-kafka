package com.example.transactionengine.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.transactionengine.transaction.api.CreateTransactionRequest;
import com.example.transactionengine.transaction.application.TransactionApplicationService;
import com.example.transactionengine.transaction.exception.IdempotencyConflictException;
import com.example.transactionengine.transaction.persistence.OutboxEvent;
import com.example.transactionengine.transaction.persistence.OutboxRepository;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "outbox.publisher.enabled=false")
@ContextConfiguration(initializers = TransactionServicePostgresIntegrationTest.Initializer.class)
class TransactionServicePostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine")
          .withDatabaseName("transactions")
          .withUsername("postgres")
          .withPassword("postgres");

  @Autowired private TransactionApplicationService transactions;
  @Autowired private JdbcTemplate jdbc;
  @SpyBean private OutboxRepository outbox;

  @AfterEach
  void resetOutboxSpy() {
    reset(outbox);
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> false);
  }

  @BeforeAll
  static void migrate() throws Exception {
    try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE ROLE transaction_app LOGIN PASSWORD 'transaction_app_dev'");
      statement.execute("CREATE ROLE transaction_migrator LOGIN PASSWORD 'transaction_migrator_dev'");
      statement.execute("CREATE SCHEMA transaction_schema AUTHORIZATION transaction_migrator");
    }

    var workingDirectory = Path.of(System.getProperty("user.dir"));
    var migrations = workingDirectory.resolve("../../infra/postgres/migrations").normalize();
    if (!Files.exists(migrations)) {
      migrations = workingDirectory.resolve("infra/postgres/migrations").normalize();
    }
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + migrations)
        .schemas("transaction_schema")
        .defaultSchema("transaction_schema")
        .placeholders(Map.of("appUser", "transaction_app"))
        .load()
        .migrate();
  }

  @Test
  void transactionAndOutboxCommitTogetherAndReplayIsStable() {
    var request = new CreateTransactionRequest("demo-acc-001", new java.math.BigDecimal("10.00"), TransactionType.DEBIT, "MXN");

    var first = transactions.create(request, "integration-key", "integration-tenant", "corr-1", null);
    var replay = transactions.create(request, "integration-key", "integration-tenant", "corr-2", null);

    assertThat(replay.transactionId()).isEqualTo(first.transactionId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.transactions WHERE idempotency_scope = 'integration-tenant' AND idempotency_key = 'integration-key'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.outbox_events WHERE aggregate_id = ?",
                Integer.class,
                first.transactionId()))
        .isEqualTo(1);
  }

  @Test
  void bodyMismatchIsRejectedByTheDatabaseBackedService() {
    var first = new CreateTransactionRequest("demo-acc-002", new java.math.BigDecimal("11.00"), TransactionType.DEBIT, "MXN");
    var different = new CreateTransactionRequest("demo-acc-002", new java.math.BigDecimal("12.00"), TransactionType.DEBIT, "MXN");

    transactions.create(first, "mismatch-key", "integration-tenant", "corr-3", null);

    assertThatThrownBy(() -> transactions.create(different, "mismatch-key", "integration-tenant", "corr-4", null))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void transactionInsertRollsBackWhenOutboxInsertFails() {
    var request = new CreateTransactionRequest("demo-acc-001", new java.math.BigDecimal("13.00"), TransactionType.DEBIT, "MXN");
    doThrow(new IllegalStateException("outbox unavailable")).when(outbox).insert(any(OutboxEvent.class));

    assertThatThrownBy(() -> transactions.create(request, "rollback-key", "integration-tenant", "corr-5", null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.transactions WHERE idempotency_scope = 'integration-tenant' AND idempotency_key = 'rollback-key'",
                Integer.class))
        .isZero();
  }
  static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of("spring.main.web-application-type=none").applyTo(context);
    }
  }
}