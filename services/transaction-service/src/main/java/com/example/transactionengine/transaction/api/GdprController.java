package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.application.GdprService;
import com.example.transactionengine.transaction.security.TenantOwnershipValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GDPR erasure endpoint (F7).
 * DELETE /customers/{accountId} — right to be forgotten for customerNote PII.
 */
@RestController
@RequestMapping("/customers")
public class GdprController {

  private final GdprService gdpr;
  private final TenantOwnershipValidator ownership;

  public GdprController(GdprService gdpr, TenantOwnershipValidator ownership) {
    this.gdpr = gdpr;
    this.ownership = ownership;
  }

  /**
   * Erases PII (customerNote) for an account.
   *
   * @param accountId account/customer id
   * @param tenantId tenant header
   * @param reason optional reason
   * @param jwt principal for audit
   * @return erasure result
   */
  @DeleteMapping(value = "/{accountId}", params = "!local")
  @PreAuthorize("hasAuthority('SCOPE_admin:gdpr') or hasAuthority('SCOPE_gdpr:write')")
  public ResponseEntity<GdprService.ErasureResult> erase(
      @PathVariable String accountId,
      @RequestHeader(name = "X-Tenant-Id", defaultValue = "demo") String tenantId,
      @RequestParam(name = "reason", required = false) String reason,
      @AuthenticationPrincipal Jwt jwt) {
    ownership.validate(tenantId, accountId);
    String requestedBy = jwt != null ? jwt.getSubject() : tenantId;
    var result = gdpr.erase(accountId, requestedBy, reason);
    return ResponseEntity.ok(result);
  }

  @DeleteMapping(value = "/{accountId}", params = "local")
  public ResponseEntity<GdprService.ErasureResult> eraseLocal(
      @PathVariable String accountId,
      @RequestHeader(name = "X-Tenant-Id", defaultValue = "demo") String tenantId,
      @RequestParam(name = "reason", required = false) String reason) {
    ownership.validate(tenantId, accountId);
    var result = gdpr.erase(accountId, tenantId + ":local", reason);
    return ResponseEntity.ok(result);
  }
}
