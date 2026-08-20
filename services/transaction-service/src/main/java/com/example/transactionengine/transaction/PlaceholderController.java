package com.example.transactionengine.transaction;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlaceholderController {

  @GetMapping("/placeholder")
  public PlaceholderResponse placeholder() {
    return new PlaceholderResponse("transaction-service", "READY", Instant.now());
  }

  public record PlaceholderResponse(String service, String status, Instant timestamp) {}
}
