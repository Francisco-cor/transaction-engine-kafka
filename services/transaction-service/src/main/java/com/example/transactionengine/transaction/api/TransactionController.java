package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.application.TransactionApplicationService;
import com.example.transactionengine.transaction.security.TenantOwnershipValidator;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for transaction ingestion and query.
 * Validates tenant ownership and delegates to transactional outbox service.
 */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

  private final TransactionApplicationService transactions;
  private final TenantOwnershipValidator ownership;

  public TransactionController(
      TransactionApplicationService transactions, TenantOwnershipValidator ownership) {
    this.transactions = transactions;
    this.ownership = ownership;
  }

  /**
   * Creates a transaction idempotently; returns 202 with Location.
   *
   * @param request request body
   * @param idempotencyKey idempotency key
   * @param tenantId tenant header
   * @param correlationId optional correlation id
   * @param traceparent optional W3C traceparent
   * @param jwt authenticated principal
   * @return 202 response
   */
  @PostMapping
  public ResponseEntity<TransactionResponse> create(
      @Valid @RequestBody CreateTransactionRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Tenant-Id", defaultValue = "demo") String tenantId,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
      @RequestHeader(name = "traceparent", required = false) String traceparent,
      @AuthenticationPrincipal Jwt jwt) {
    String effectiveTenant = resolveTenant(tenantId, jwt);
    ownership.validate(effectiveTenant, request.accountId());
    var response =
        transactions.create(request, idempotencyKey, effectiveTenant, correlationId, traceparent);
    return ResponseEntity.accepted()
        .location(URI.create("/transactions/" + response.transactionId()))
        .body(response);
  }

  private static String resolveTenant(String headerTenant, Jwt jwt) {
    if (jwt != null) {
      String tenantClaim = jwt.getClaimAsString("tenant");
      if (tenantClaim != null && !tenantClaim.isBlank()) {
        return tenantClaim;
      }
      String subject = jwt.getSubject();
      if (subject != null && subject.contains(":")) {
        return subject.split(":")[0];
      }
    }
    return headerTenant != null ? headerTenant : "demo";
  }

  /**
   * Retrieves a transaction by id.
   *
   * @param transactionId transaction id
   * @param correlationId optional correlation id
   * @return transaction response
   */
  @GetMapping("/{transactionId}")
  public TransactionResponse get(
      @PathVariable UUID transactionId,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
    return transactions.get(transactionId, correlationId);
  }
}