package com.example.transactionengine.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.application.ProcessingOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies pessimistic locking serializes concurrent debits on the same account and that
 * invariants from IMPLEMENTATION_PLAN.md:599 hold under contention:
 * balance_final == balance_inicial + sum(ledger_entries).
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "ledger.outbox.publisher-enabled=false",
      "spring.kafka.bootstrap-servers=localhost:9092",
      "spring.kafka.listener.auto-startup=false"
    })
class LedgerConcurrentBalanceIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine")
          .withDatabaseName("transactions")
          .withUsername("postgres")
          .withPassword("postgres");

  @Autowired private LedgerApplicationService ledger;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> false);
  }

  @BeforeAll
  static void migrate() throws Exception {
    try (var connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE ROLE IF NOT EXISTS transaction_app LOGIN PASSWORD 'transaction_app_dev'");
      statement.execute("CREATE ROLE IF NOT EXISTS transaction_migrator LOGIN PASSWORD 'transaction_migrator_dev'");
      statement.execute("CREATE SCHEMA IF NOT EXISTS transaction_schema AUTHORIZATION transaction_migrator");
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
  void hotAccountWithFiveConcurrentDebitsOnlyOneCommitsDueToFunds() throws Exception {
    var accountId = "hot-concurrent-" + UUID.randomUUID();
    insertAccount(accountId, "100.00");
    var transactionIds = new ArrayList<UUID>();
    var events = new ArrayList<TransactionCreatedV1>();
    for (int i = 0; i < 5; i++) {
      var txId = insertTransaction(accountId, "60.00");
      transactionIds.add(txId);
      events.add(event(txId, accountId, "60.00", "DEBIT"));
    }

    ExecutorService executor = Executors.newFixedThreadPool(5);
    try {
      List<CompletableFuture<ProcessingOutcome>> futures =
          events.stream()
              .map(e -> CompletableFuture.supplyAsync(() -> process(e), executor))
              .toList();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

      var outcomes = futures.stream().map(CompletableFuture::join).toList();
      long committed = outcomes.stream().filter(o -> o == ProcessingOutcome.COMMITTED).count();
      long rejected = outcomes.stream().filter(o -> o == ProcessingOutcome.REJECTED).count();

      assertThat(committed).isEqualTo(1);
      assertThat(rejected).isEqualTo(4);

      var balance =
          jdbc.queryForObject(
              "SELECT available_balance FROM transaction_schema.accounts WHERE account_id = ?",
              BigDecimal.class,
              accountId);
      assertThat(balance).isEqualByComparingTo("40.00");

      var ledgerCount =
          jdbc.queryForObject(
              "SELECT count(*) FROM transaction_schema.ledger_entries WHERE account_id = ?",
              Integer.class,
              accountId);
      assertThat(ledgerCount).isEqualTo(1);

      // Invariant: no duplicate ledger per transaction
      for (var txId : transactionIds) {
        var perTx =
            jdbc.queryForObject(
                "SELECT count(*) FROM transaction_schema.ledger_entries WHERE transaction_id = ?",
                Integer.class,
                txId);
        assertThat(perTx).isIn(0, 1);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentCreditsSumBalancesCorrectly() throws Exception {
    var accountId = "credit-concurrent-" + UUID.randomUUID();
    insertAccount(accountId, "0.00");
    var events = new ArrayList<TransactionCreatedV1>();
    for (int i = 0; i < 3; i++) {
      var txId = insertTransaction(accountId, "10.00", "CREDIT");
      events.add(event(txId, accountId, "10.00", "CREDIT"));
    }

    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      List<CompletableFuture<ProcessingOutcome>> futures =
          events.stream()
              .map(e -> CompletableFuture.supplyAsync(() -> process(e), executor))
              .toList();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
      var outcomes = futures.stream().map(CompletableFuture::join).toList();
      assertThat(outcomes).allMatch(o -> o == ProcessingOutcome.COMMITTED);

      var balance =
          jdbc.queryForObject(
              "SELECT available_balance FROM transaction_schema.accounts WHERE account_id = ?",
              BigDecimal.class,
              accountId);
      assertThat(balance).isEqualByComparingTo("30.00");

      var ledgerSum =
          jdbc.queryForObject(
              "SELECT COALESCE(sum(amount),0) FROM transaction_schema.ledger_entries WHERE account_id = ?",
              BigDecimal.class,
              accountId);
      assertThat(ledgerSum).isEqualByComparingTo("30.00");
    } finally {
      executor.shutdownNow();
    }
  }

  private ProcessingOutcome process(TransactionCreatedV1 event) {
    try {
      return ledger.process(event, objectMapper.writeValueAsString(event), null, null);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID insertTransaction(String accountId, String amount) {
    return insertTransaction(accountId, amount, "DEBIT");
  }

  private UUID insertTransaction(String accountId, String amount, String type) {
    var transactionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO transaction_schema.transactions (
            transaction_id, idempotency_scope, idempotency_key, request_hash,
            account_id, amount, currency, type, status
        ) VALUES (?, ?, ?, ?, ?, ?, 'MXN', ?, 'PENDING')
        """,
        transactionId,
        "concurrent-" + transactionId,
        "key-" + transactionId,
        "hash-" + transactionId,
        accountId,
        new BigDecimal(amount),
        type);
    return transactionId;
  }

  private void insertAccount(String accountId, String balance) {
    jdbc.update(
        "INSERT INTO transaction_schema.accounts (account_id, currency, available_balance, status) VALUES (?, 'MXN', ?, 'ACTIVE')",
        accountId,
        new BigDecimal(balance));
  }

  private static TransactionCreatedV1 event(
      UUID transactionId, String accountId, String amount, String type) {
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
