package com.example.transactionengine.reconciliation.api;

import com.example.transactionengine.reconciliation.application.ReconciliationApplicationService;
import com.example.transactionengine.reconciliation.domain.ReconciliationResultView;
import com.example.transactionengine.reconciliation.domain.ReplayRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

  private final ReconciliationApplicationService reconciliation;

  public ReconciliationController(ReconciliationApplicationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @GetMapping("/{transactionId}")
  public ReconciliationResultView get(@PathVariable UUID transactionId) {
    return reconciliation.get(transactionId);
  }

  @PostMapping("/{transactionId}/replay")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasAuthority('SCOPE_admin:replay') or @securityChecker.isSecurityDisabled()")
  public ReplayRequest replay(
      @PathVariable UUID transactionId,
      @RequestHeader(name = "X-Replay-Reason", required = false) String reason,
      @RequestParam(name = "dryRun", required = false, defaultValue = "false") boolean dryRun,
      @AuthenticationPrincipal Jwt jwt) {
    String requestedBy = jwt != null ? jwt.getSubject() : "anonymous";
    if (jwt != null && jwt.getClaimAsString("preferred_username") != null) {
      requestedBy = jwt.getClaimAsString("preferred_username");
    }
    return reconciliation.replay(transactionId, reason, dryRun, requestedBy);
  }
}
