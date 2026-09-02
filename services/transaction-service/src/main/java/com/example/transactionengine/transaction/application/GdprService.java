package com.example.transactionengine.transaction.application;

import com.example.transactionengine.security.AuditLogger;
import com.example.transactionengine.security.VaultTransitClient;
import com.example.transactionengine.transaction.persistence.GdprRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR right to be forgotten (F7).
 * Tokenized customerNote via Vault Transit is erased by scrubbing payload and
 * recording erasure audit; financial ledger entries remain (append-only) but PII is removed.
 */
@Service
public class GdprService {

  private static final Logger log = LoggerFactory.getLogger(GdprService.class);

  private final GdprRepository gdpr;
  private final VaultTransitClient vault;
  private final AuditLogger audit;

  public GdprService(GdprRepository gdpr, VaultTransitClient vault, AuditLogger audit) {
    this.gdpr = gdpr;
    this.vault = vault;
    this.audit = audit;
  }

  @Transactional
  public ErasureResult erase(String accountId, String requestedBy, String reason) {
    String effectiveBy = requestedBy != null ? requestedBy : "anonymous";
    String effectiveReason = reason != null ? reason : "gdpr-erasure";
    // Record erasure request (idempotent on account_id)
    gdpr.recordErasure(accountId, effectiveBy, effectiveReason);
    int scrubbed = gdpr.scrubCustomerNote(accountId);
    int anonymized = gdpr.anonymizeTransactions(accountId);
    log.info("GDPR erasure account={} by={} scrubbed={} anonymized={}", accountId, effectiveBy, scrubbed, anonymized);
    if (audit != null) {
      audit.logReplay(accountId, effectiveBy, "gdpr-erasure:" + effectiveReason, false, "ERASED");
    }
    // Vault: if enabled, token cannot be reversed without key; rotation simulates forgetting
    if (vault != null && vault.isEnabled()) {
      log.info("Vault transit key retains ciphertext but PII scrubbed from payload for account {}", accountId);
    }
    return new ErasureResult(accountId, scrubbed, anonymized);
  }

  public record ErasureResult(String accountId, int scrubbedOutbox, int anonymizedTx) {}
}
