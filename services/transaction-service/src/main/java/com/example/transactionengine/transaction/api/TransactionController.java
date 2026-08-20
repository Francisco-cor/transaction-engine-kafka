package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.application.TransactionApplicationService;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

  private final TransactionApplicationService transactions;

  public TransactionController(TransactionApplicationService transactions) {
    this.transactions = transactions;
  }

  @PostMapping
  public ResponseEntity<TransactionResponse> create(
      @Valid @RequestBody CreateTransactionRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Tenant-Id", defaultValue = "demo") String tenantId,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
      @RequestHeader(name = "traceparent", required = false) String traceparent) {
    var response =
        transactions.create(request, idempotencyKey, tenantId, correlationId, traceparent);
    return ResponseEntity.accepted()
        .location(URI.create("/transactions/" + response.transactionId()))
        .body(response);
  }

  @GetMapping("/{transactionId}")
  public TransactionResponse get(
      @PathVariable UUID transactionId,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
    return transactions.get(transactionId, correlationId);
  }
}