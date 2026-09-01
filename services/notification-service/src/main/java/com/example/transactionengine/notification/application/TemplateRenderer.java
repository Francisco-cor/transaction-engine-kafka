package com.example.transactionengine.notification.application;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TemplateRenderer {
  public String render(UUID transactionId, String accountId, BigDecimal amount, String currency, String type) {
    return String.format("Account %s %s %s %s (tx %s) - thank you", accountId, type, amount.toPlainString(), currency, transactionId);
  }
}
