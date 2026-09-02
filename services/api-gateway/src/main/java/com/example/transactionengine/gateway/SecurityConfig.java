package com.example.transactionengine.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  // F3 mTLS: when GATEWAY_MTLS_ENABLED=true, gateway validates client cert via X-Forwarded-Client-Cert
  // In compose dev mTLS is off; in K8s with Linkerd/cert-manager it is on (see infra/k8s/networkpolicy.yaml).
  @Value("${GATEWAY_SECURITY_ENABLED:true}")
  private boolean securityEnabled;

  @Bean
  SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
    if (!securityEnabled) {
      return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
          .authorizeExchange(ex -> ex.anyExchange().permitAll())
          .build();
    }
    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            ex ->
                ex.pathMatchers("/actuator/**")
                    .permitAll()
                    .pathMatchers("/reconciliation/*/replay")
                    .hasAuthority("SCOPE_admin:replay")
                    .pathMatchers("/transactions/**")
                    .hasAnyAuthority("SCOPE_transactions:write", "SCOPE_transactions:read")
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    // Default extracts scopes from 'scope' or 'scp' claim prefixed with SCOPE_
    return new ReactiveJwtAuthenticationConverterAdapter(converter);
  }
}
