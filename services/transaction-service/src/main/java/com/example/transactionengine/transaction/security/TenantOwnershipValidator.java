package com.example.transactionengine.transaction.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Validates that tenant owns accountId. For demo, simple prefix rule:
 * demo owns demo-acc-*, otherwise tenant must be prefix of accountId.
 * Production would query accounts table tenant column.
 */
@Component
public class TenantOwnershipValidator {

  public void validate(String tenantId, String accountId) {
    if (tenantId == null || accountId == null) {
      throw new AccessDeniedException("Missing tenant or account");
    }
    String tenant = tenantId.trim();
    String account = accountId.trim();
    // Demo tenant owns demo-acc-*
    if ("demo".equals(tenant)) {
      if (account.startsWith("demo-acc-") || account.startsWith("hot-account-") || account.startsWith("hot-") || account.startsWith("credit-")) {
        return;
      }
      // Allow demo to own any demo-prefixed account, else deny
      throw new AccessDeniedException("Tenant demo does not own account " + account);
    }
    // Otherwise account must be prefixed with tenant
    if (account.equals(tenant) || account.startsWith(tenant + "-") || account.startsWith(tenant + "_")) {
      return;
    }
    throw new AccessDeniedException("Tenant " + tenant + " does not own account " + account);
  }
}
