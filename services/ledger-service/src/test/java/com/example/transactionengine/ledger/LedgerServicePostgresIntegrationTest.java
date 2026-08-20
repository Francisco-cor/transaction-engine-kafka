package com.example.transactionengine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.application.ProcessingOutcome;
import com.example.transactionengine.ledger.persistence.OutboxEvent;
import com.example.transactionengine.ledger.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
      "ledger.outbox.publisher-enabled=false",
      "spring.kafka.bootstrap-servers=localhost:9092",
      "spring.kafka.listener.auto-startup=false"
    })
class LedgerServicePostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine")
          .withDatabaseName("transactions")
          .withUsername("postgres")
          .withPassword("postgres");

  @Autowired private LedgerApplicationService ledger;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @SpyBean private OutboxRepository outbox;

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

  @AfterEach
  void resetOutboxSpy() {
    reset(outbox);
  }

  @Test
  void duplicateAndCrashAfterCommitDoNotDuplicateLedger() throws Exception {
    var transactionId = insertTransaction("demo-acc-001", "10.00");
    var event = event(transactionId, "demo-acc-001", "10.00", "DEBIT");
    var payload = objectMapper.writeValueAsString(event);

    var acknowledgment = org.mockito.Mockito.mock(Acknowledgment.class);
    var crashingListener =
        new com.example.transactionengine.ledger.messaging.LedgerListener(ledger, objectMapper) {
          @Override
          protected void beforeAck() {
            throw new IllegalStateException("simulated crash after local commit");
          }
        };
    var record =
        new ConsumerRecord<String, String>(
            "transactions.created.v1", 0, 1L, "demo-acc-001", payload);
    assertThatThrownBy(() -> crashingListener.onMessage(record, acknowledgment))
        .isInstanceOf(IllegalStateException.class);
    verify(acknowledgment, never()).acknowledge();

    var redeliveryAck = org.mockito.Mockito.mock(Acknowledgment.class);
    new com.example.transactionengine.ledger.messaging.LedgerListener(ledger, objectMapper)
        .onMessage(record, redeliveryAck);
    verify(redeliveryAck).acknowledge();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transactionId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT duplicate_count FROM transaction_schema.inbox_events WHERE event_id = ?",
                Integer.class,
                event.eventId()))
        .isEqualTo(1);
  }

  @Test
  void twoConcurrentDebitsSerializeOnAccountRow() throws Exception {
    var accountId = "hot-account-" + UUID.randomUUID();
    insertAccount(accountId, "100.00");
    var firstId = insertTransaction(accountId, "100.00");
    var secondId = insertTransaction(accountId, "100.00");
    var first = event(firstId, accountId, "100.00", "DEBIT");
    var second = event(secondId, accountId, "100.00", "DEBIT");
    var executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<ProcessingOutcome> firstResult =
          CompletableFuture.supplyAsync(() -> process(first), executor);
      CompletableFuture<ProcessingOutcome> secondResult =
          CompletableFuture.supplyAsync(() -> process(second), executor);
      CompletableFuture.allOf(firstResult, secondResult).join();

      assertThat(java.util.List.of(firstResult.join(), secondResult.join()))
          .containsExactlyInAnyOrder(ProcessingOutcome.COMMITTED, ProcessingOutcome.REJECTED);
      assertThat(
              jdbc.queryForObject(
                  "SELECT available_balance FROM transaction_schema.accounts WHERE account_id = ?",
                  BigDecimal.class,
                  accountId))
          .isEqualByComparingTo("0.00");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM transaction_schema.ledger_entries WHERE account_id = ?",
                  Integer.class,
                  accountId))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void databaseRollbackRemovesInboxLedgerAndStatusChangeWhenOutcomeOutboxFails() {
    var transactionId = insertTransaction("demo-acc-002", "5.00");
    var event = event(transactionId, "demo-acc-002", "5.00", "DEBIT");
    var payload = "payload";
    doThrow(new IllegalStateException("outcome outbox unavailable"))
        .when(outbox)
        .insert(any(OutboxEvent.class));

    assertThatThrownBy(() -> ledger.process(event, payload, null, null))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM transaction_schema.transactions WHERE transaction_id = ?",
                String.class,
                transactionId))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.inbox_events WHERE event_id = ?",
                Integer.class,
                event.eventId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transactionId))
        .isZero();
  }

  private ProcessingOutcome process(TransactionCreatedV1 event) {
    try {
      return ledger.process(event, objectMapper.writeValueAsString(event), null, null);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID insertTransaction(String accountId, String amount) {
    var transactionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO transaction_schema.transactions (
            transaction_id, idempotency_scope, idempotency_key, request_hash,
            account_id, amount, currency, type, status
        ) VALUES (?, ?, ?, ?, ?, ?, 'MXN', 'DEBIT', 'PENDING')
        """,
        transactionId,
        "integration-" + transactionId,
        "key-" + transactionId,
        "hash-" + transactionId,
        accountId,
        new BigDecimal(amount));
    return transactionId;
  }

  private void insertAccount(String accountId, String balance) {
    jdbc.update(
        "INSERT INTO transaction_schema.accounts (account_id, currency, available_balance, status) VALUES (?, 'MXN', ?, 'ACTIVE')",
        accountId,
        new BigDecimal(balance));
  }

  private static TransactionCreatedV1 event(UUID transactionId, String accountId, String amount, String type) {
    return new TransactionCreatedV1(
        UUID.randomUUID(),
        "TransactionCreated",
        1,
        Instant.parse("2026-08-20T17:00:00Z"),
        transactionId,
        accountId,
        new BigDecimal(amount),
        "MXN",
        type,
        Map.of());
  }
}