package com.example.transactionengine.reconciliation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("securityChecker")
public class SecurityChecker {
  @Value("${reconciliation.security.enabled:false}")
  private boolean enabled;

  public boolean isSecurityDisabled() {
    return !enabled;
  }
}
