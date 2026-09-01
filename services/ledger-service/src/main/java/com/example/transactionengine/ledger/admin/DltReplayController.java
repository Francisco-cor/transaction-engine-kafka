package com.example.transactionengine.ledger.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/dlt")
public class DltReplayController {

  private final DltReplayService replayService;
  private final boolean securityEnabled;

  public DltReplayController(
      DltReplayService replayService,
      @org.springframework.beans.factory.annotation.Value("${ledger.security.enabled:false}") boolean securityEnabled) {
    this.replayService = replayService;
    this.securityEnabled = securityEnabled;
  }

  @PostMapping("/replay")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasAuthority('SCOPE_admin:replay') or @ledgerSecurityChecker.isSecurityDisabled()")
  public DltReplayService.DltReplayResult replay(
      @RequestParam String topic,
      @RequestParam int partition,
      @RequestParam long offset,
      @RequestBody(required = false) String payload,
      @RequestHeader(value = "X-Replay-Reason", required = false) String reason,
      @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun,
      @AuthenticationPrincipal Jwt jwt) {
    String requestedBy = jwt != null ? jwt.getSubject() : "anonymous";
    if (jwt != null && jwt.getClaimAsString("preferred_username") != null) {
      requestedBy = jwt.getClaimAsString("preferred_username");
    }
    String effectiveReason = (reason == null || reason.isBlank()) ? "manual-dlt-replay" : reason;
    return replayService.replay(topic, partition, offset, payload != null ? payload : "{}", effectiveReason, requestedBy, dryRun);
  }

  @GetMapping("/health")
  public String health() {
    return "DLT replay admin OK - securityEnabled=" + securityEnabled;
  }
}
