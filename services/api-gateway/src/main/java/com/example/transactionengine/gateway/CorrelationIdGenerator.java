package com.example.transactionengine.gateway;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CorrelationIdGenerator {
  public String generate() {
    return UUID.randomUUID().toString();
  }
}
