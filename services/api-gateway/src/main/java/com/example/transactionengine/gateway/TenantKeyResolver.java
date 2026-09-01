package com.example.transactionengine.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component("tenantKeyResolver")
public class TenantKeyResolver implements KeyResolver {
  @Override
  public Mono<String> resolve(ServerWebExchange exchange) {
    String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
    String ip = exchange.getRequest().getRemoteAddress() != null
        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        : "unknown";
    if (tenant != null && !tenant.isBlank()) {
      return Mono.just("tenant:" + tenant.trim());
    }
    return Mono.just("ip:" + ip);
  }
}
