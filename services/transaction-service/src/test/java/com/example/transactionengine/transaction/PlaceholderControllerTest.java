package com.example.transactionengine.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlaceholderController.class)
class PlaceholderControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void exposesReadyPlaceholderEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/v1/placeholder"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("transaction-service"))
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.timestamp").exists());
  }
}
