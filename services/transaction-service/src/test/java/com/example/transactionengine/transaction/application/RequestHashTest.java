package com.example.transactionengine.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.transaction.api.CreateTransactionRequest;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RequestHashTest {

  @Test
  void sameLogicalRequestProducesSameHash() {
    var first = request("483.2100");
    var second = request("483.21");

    assertThat(RequestHash.sha256(first)).isEqualTo(RequestHash.sha256(second));
  }

  @Test
  void changingAnyBusinessFieldChangesHash() {
    var original = request("483.21");
    var differentAccount = new CreateTransactionRequest("other-account", original.amount(), original.type(), original.currency());

    assertThat(RequestHash.sha256(original)).isNotEqualTo(RequestHash.sha256(differentAccount));
  }

  private static CreateTransactionRequest request(String amount) {
    return new CreateTransactionRequest("demo-acc-001", new BigDecimal(amount), TransactionType.DEBIT, "MXN");
  }
}