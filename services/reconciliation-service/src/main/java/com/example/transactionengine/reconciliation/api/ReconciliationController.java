package com.example.transactionengine.reconciliation.api;

import com.example.transactionengine.reconciliation.application.ReconciliationApplicationService;
import com.example.transactionengine.reconciliation.domain.ReconciliationResultView;
import com.example.transactionengine.reconciliation.domain.ReplayRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
  public ReplayRequest replay(
      @PathVariable UUID transactionId,
      @RequestHeader(name = "X-Replay-Reason", required = false) String reason) {
    return reconciliation.replay(transactionId, reason);
  }
}
