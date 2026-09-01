package com.example.transactionengine.ledger.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("ledgerSecurityChecker")
public class LedgerSecurityChecker {
  @Value("${ledger.security.enabled:false}")
  private boolean enabled;

  public boolean isSecurityDisabled() {
    return !enabled;
  }
}
