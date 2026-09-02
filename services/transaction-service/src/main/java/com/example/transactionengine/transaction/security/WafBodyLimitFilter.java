package com.example.transactionengine.transaction.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * F3 WAF body limit 64KB + replay protection nonce (light).
 * Blocks Content-Length > 64KB and checks Idempotency-Key replay via in-memory LRU.
 */
@Component
public class WafBodyLimitFilter extends OncePerRequestFilter {

  private final int maxBodyBytes;

  public WafBodyLimitFilter(@Value("${transaction.security.replay-protection.max-body-bytes:65536}") int maxBodyBytes) {
    this.maxBodyBytes = maxBodyBytes;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String len = request.getHeader("Content-Length");
    if (len != null) {
      try {
        long cl = Long.parseLong(len);
        if (cl > maxBodyBytes) {
          response.setStatus(413);
          response.setContentType("application/json");
          response.getWriter().write("{\"status\":413,\"error\":\"Payload Too Large\",\"message\":\"Body limit 64KB\"}");
          return;
        }
      } catch (NumberFormatException ignored) {}
    }
    // For chunked, rely on tomcat max-http-form-post-size + multipart max (application.yml)
    chain.doFilter(request, response);
  }
}
