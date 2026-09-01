package com.example.transactionengine.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures every HTTP request has correlation_id and trace context in MDC.
 */
@Component
public class MdcFilter extends OncePerRequestFilter {

  public static final String CORRELATION_HEADER = "X-Correlation-Id";
  public static final String TRACEPARENT_HEADER = "traceparent";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = request.getHeader(CORRELATION_HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    String traceparent = request.getHeader(TRACEPARENT_HEADER);
    traceparent = TraceContext.resolve(traceparent);

    MDC.put("correlation_id", correlationId);
    MDC.put("traceparent", traceparent);
    response.setHeader(CORRELATION_HEADER, correlationId);
    response.setHeader(TRACEPARENT_HEADER, traceparent);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove("correlation_id");
      MDC.remove("traceparent");
      TraceContext.clearMdc();
    }
  }
}
