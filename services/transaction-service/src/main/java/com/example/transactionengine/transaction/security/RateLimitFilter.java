package com.example.transactionengine.transaction.security;

import io.bucket4j.Bandwidth;
import io.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Simple in-memory rate limiting per tenant/IP using Bucket4j.
 * For distributed limit, api-gateway uses Redis; this is second line of defense.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final int rps;
  private final int burst;

  public RateLimitFilter(
      @Value("${transaction.rate-limit.rps:50}") int rps,
      @Value("${transaction.rate-limit.burst:100}") int burst) {
    this.rps = rps;
    this.burst = burst;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/transactions")) {
      chain.doFilter(request, response);
      return;
    }
    String key = resolveKey(request);
    Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(429);
      response.setHeader("Retry-After", "1");
      response.setContentType("application/json");
      response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for \"}"
          .replace("{}", key));
    }
  }

  private String resolveKey(HttpServletRequest request) {
    String tenant = request.getHeader("X-Tenant-Id");
    if (tenant != null && !tenant.isBlank()) {
      return "tenant:" + tenant.trim();
    }
    String ip = request.getRemoteAddr();
    return "ip:" + (ip != null ? ip : "unknown");
  }

  private Bucket newBucket() {
    Bandwidth limit = Bandwidth.builder().capacity(burst).refillGreedy(rps, Duration.ofSeconds(1)).build();
    return Bucket.builder().addLimit(limit).build();
  }
}
