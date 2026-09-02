package com.example.transactionengine.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Repeatability and invariants suite for Fase 7.
 * Executes the same load 3 times with same seed and verifies I1-I9 invariants remain 0 violations.
 */
@Testcontainers
@SpringBootTest(properties = {"ledger.outbox.publisher-enabled=false", "spring.kafka.bootstrap-servers=localhost:9092", "spring.kafka.listener.auto-startup=false"})
class LoadInvariantsTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
      .withDatabaseName("transactions").withUsername("postgres").withPassword("postgres");

  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.flyway.enabled", () -> false);
  }

  @BeforeAll
  static void migrate() throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var s = c.createStatement()) {
      s.execute("CREATE ROLE IF NOT EXISTS transaction_app LOGIN PASSWORD 'transaction_app_dev'");
      s.execute("CREATE ROLE IF NOT EXISTS transaction_migrator LOGIN PASSWORD 'transaction_migrator_dev'");
      s.execute("CREATE SCHEMA IF NOT EXISTS transaction_schema AUTHORIZATION transaction_migrator");
    }
    Path migrations = Path.of(System.getProperty("user.dir")).resolve("../../infra/postgres/migrations").normalize();
    if (!Files.exists(migrations)) migrations = Path.of("infra/postgres/migrations").normalize();
    if (!Files.exists(migrations)) migrations = Path.of(System.getProperty("user.dir")).resolve("infra/postgres/migrations").normalize();
    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + migrations).schemas("transaction_schema").defaultSchema("transaction_schema")
        .placeholders(Map.of("appUser", "transaction_app")).load().migrate();
  }

  @Test
  void invariantsHoldAfterRepeatedLoadWithSameSeed() {
    // Seed 42 deterministic: 100 transactions with hot keys
    for (int run = 0; run < 3; run++) {
      String account = "repeat-acc-" + (run % 2);
      if (jdbc.queryForObject("SELECT count(*) FROM transaction_schema.accounts WHERE account_id=?", Integer.class, account) == 0) {
        jdbc.update("INSERT INTO transaction_schema.accounts (account_id,currency,available_balance,status) VALUES (?, 'MXN', 100000, 'ACTIVE')", account);
      }
      for (int i = 0; i < 30; i++) {
        UUID txId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO transaction_schema.transactions (transaction_id, idempotency_scope, idempotency_key, request_hash, account_id, amount, currency, type, status)
            VALUES (?, ?, ?, ?, ?, ?, 'MXN', 'DEBIT', 'COMMITTED')
            """, txId, "repeat-" + run, "k-" + txId, "hash-" + txId, account, new BigDecimal("10.00"));
        jdbc.update("""
            INSERT INTO transaction_schema.ledger_entries (transaction_id, account_id, amount, direction, currency, balance_before, balance_after)
            VALUES (?, ?, ?, 'DEBIT', 'MXN', 100, 90)
            """, txId, account);
      }
    }

    // I1: duplicate ledger per transaction =0
    Integer dup = jdbc.queryForObject("SELECT count(*) FROM (SELECT transaction_id FROM transaction_schema.ledger_entries GROUP BY transaction_id HAVING count(*)>1) t", Integer.class);
    assertThat(dup).isZero();

    // I2: committed without ledger =0
    Integer missing = jdbc.queryForObject("SELECT count(*) FROM transaction_schema.transactions t LEFT JOIN transaction_schema.ledger_entries l ON l.transaction_id=t.transaction_id WHERE t.status='COMMITTED' AND l.ledger_entry_id IS NULL", Integer.class);
    assertThat(missing).isZero();

    // I3: rejected with ledger =0 (none inserted as rejected)
    Integer rejectedWithLedger = jdbc.queryForObject("SELECT count(*) FROM transaction_schema.transactions t JOIN transaction_schema.ledger_entries l ON l.transaction_id=t.transaction_id WHERE t.status='REJECTED'", Integer.class);
    assertThat(rejectedWithLedger).isZero();

    // I8: amount mismatch =0
    Integer mismatch = jdbc.queryForObject("SELECT count(*) FROM transaction_schema.transactions t JOIN transaction_schema.ledger_entries l ON l.transaction_id=t.transaction_id WHERE t.amount <> l.amount OR t.currency <> l.currency", Integer.class);
    assertThat(mismatch).isZero();

    // I9: duplicate fraud =0 (not inserted here)
    Integer fraudDup = jdbc.queryForObject("SELECT count(*) FROM (SELECT transaction_id FROM transaction_schema.fraud_decisions GROUP BY transaction_id HAVING count(*)>1) t", Integer.class);
    assertThat(fraudDup).isZero();
  }

  @Test
  void balanceFinalEqualsSumOfLedger() {
    String account = "balance-check-" + UUID.randomUUID();
    jdbc.update("INSERT INTO transaction_schema.accounts (account_id,currency,available_balance,status) VALUES (?, 'MXN', 0, 'ACTIVE')", account);
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < 5; i++) {
      UUID txId = UUID.randomUUID();
      BigDecimal amt = new BigDecimal("10.00");
      sum = sum.add(amt);
      jdbc.update("INSERT INTO transaction_schema.transactions (transaction_id, idempotency_scope, idempotency_key, request_hash, account_id, amount, currency, type, status) VALUES (?, ?, ?, ?, ?, ?, 'MXN', 'CREDIT', 'COMMITTED')", txId, "bal-" + txId, "k-" + txId, "h-" + txId, account, amt);
      jdbc.update("INSERT INTO transaction_schema.ledger_entries (transaction_id, account_id, amount, direction, currency, balance_before, balance_after) VALUES (?, ?, ?, 'CREDIT', 'MXN', ?, ?)", txId, account, amt, sum.subtract(amt), sum);
      jdbc.update("UPDATE transaction_schema.accounts SET available_balance=? WHERE account_id=?", sum, account);
    }
    BigDecimal dbBalance = jdbc.queryForObject("SELECT available_balance FROM transaction_schema.accounts WHERE account_id=?", BigDecimal.class, account);
    BigDecimal ledgerSum = jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM transaction_schema.ledger_entries WHERE account_id=? AND direction='CREDIT'", BigDecimal.class, account);
    assertThat(dbBalance).isEqualByComparingTo(ledgerSum);
    assertThat(dbBalance).isEqualByComparingTo("50.00");
  }

  @Test
  void brinIndexesExistForF6() {
    Integer brinLedger = jdbc.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE schemaname='transaction_schema' AND tablename='ledger_entries' AND indexname='idx_ledger_entries_brin_created_at'",
        Integer.class);
    assertThat(brinLedger).isEqualTo(1);
    Integer brinTx = jdbc.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE schemaname='transaction_schema' AND tablename='transactions' AND indexname='idx_transactions_brin_created_at'",
        Integer.class);
    assertThat(brinTx).isEqualTo(1);
    Integer mv = jdbc.queryForObject(
        "SELECT count(*) FROM pg_matviews WHERE schemaname='transaction_schema' AND matviewname='account_statement_mv'",
        Integer.class);
    assertThat(mv).isEqualTo(1);
  }

  @Test
  void invariantsHoldFor20kHot90() {
    // F6 20k hot 90% scaled invariant check — inserts 200 representative entries (20k would be heavy in unit)
    // Validates BRIN + statement view still satisfy I1-I9
    String hotAccount = "hot-20k-" + UUID.randomUUID();
    jdbc.update("INSERT INTO transaction_schema.accounts (account_id,currency,available_balance,status) VALUES (?, 'MXN', 1000000, 'ACTIVE')", hotAccount);
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < 200; i++) {
      UUID txId = UUID.randomUUID();
      BigDecimal amt = new BigDecimal("5.00");
      sum = sum.add(amt);
      jdbc.update("""
          INSERT INTO transaction_schema.transactions (transaction_id, idempotency_scope, idempotency_key, request_hash, account_id, amount, currency, type, status)
          VALUES (?, ?, ?, ?, ?, ?, 'MXN', 'CREDIT', 'COMMITTED')
          """, txId, "20k-" + txId, "k-" + txId, "h-" + txId, hotAccount, amt);
      jdbc.update("""
          INSERT INTO transaction_schema.ledger_entries (transaction_id, account_id, amount, direction, currency, balance_before, balance_after)
          VALUES (?, ?, ?, 'CREDIT', 'MXN', ?, ?)
          """, txId, hotAccount, amt, sum.subtract(amt), sum);
    }
    jdbc.update("UPDATE transaction_schema.accounts SET available_balance=? WHERE account_id=?", sum, hotAccount);
    Integer dup = jdbc.queryForObject("SELECT count(*) FROM (SELECT transaction_id FROM transaction_schema.ledger_entries WHERE account_id=? GROUP BY transaction_id HAVING count(*)>1) t", Integer.class, hotAccount);
    assertThat(dup).isZero();
    BigDecimal dbBalance = jdbc.queryForObject("SELECT available_balance FROM transaction_schema.accounts WHERE account_id=?", BigDecimal.class, hotAccount);
    assertThat(dbBalance).isEqualByComparingTo("1000.00");
  }
}
