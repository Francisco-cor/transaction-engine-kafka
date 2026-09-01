package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.persistence.StatementRepository;
import com.example.transactionengine.transaction.security.TenantOwnershipValidator;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class StatementController {

  private final StatementRepository statements;
  private final TenantOwnershipValidator ownership;

  public StatementController(StatementRepository statements, TenantOwnershipValidator ownership) {
    this.statements = statements;
    this.ownership = ownership;
  }

  @GetMapping("/{accountId}/statement")
  public StatementResponse statement(
      @PathVariable String accountId,
      @RequestHeader(name = "X-Tenant-Id", defaultValue = "demo") String tenantId,
      @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
    ownership.validate(tenantId, accountId);
    int safeLimit = Math.min(Math.max(1, limit), 100);
    return statements.getStatement(accountId, safeLimit);
  }
}
