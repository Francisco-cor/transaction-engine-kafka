package com.example.transactionengine.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.transactionengine.transaction.application.TransactionApplicationService;
import com.example.transactionengine.transaction.domain.TransactionStatus;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private TransactionApplicationService transactions;

  @Test
  void acceptsValidTransactionAndReturnsLocation() throws Exception {
    var transactionId = UUID.randomUUID();
    when(transactions.create(any(), any(), any(), any(), any()))
        .thenReturn(
            new TransactionResponse(
                transactionId,
                TransactionStatus.PENDING,
                "demo-acc-001",
                new BigDecimal("483.2100"),
                "MXN",
                TransactionType.DEBIT,
                null,
                Instant.parse("2026-08-20T17:00:00Z"),
                Instant.parse("2026-08-20T17:00:00Z"),
                "corr-1"));

    mockMvc
        .perform(
            post("/transactions")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\"demo-acc-001\",\"amount\":483.21,\"type\":\"DEBIT\",\"currency\":\"MXN\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/transactions/" + transactionId))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.transactionId").value(transactionId.toString()));
  }

  @Test
  void rejectsInvalidAmountBeforeCallingApplicationService() throws Exception {
    mockMvc
        .perform(
            post("/transactions")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\"demo-acc-001\",\"amount\":0,\"type\":\"DEBIT\",\"currency\":\"MXN\"}"))
        .andExpect(status().isBadRequest());
  }
}