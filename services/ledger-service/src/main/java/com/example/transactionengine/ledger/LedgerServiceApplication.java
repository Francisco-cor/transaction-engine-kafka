package com.example.transactionengine.ledger;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LedgerServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerServiceApplication.class, args);
  }

  @Bean
  Clock utcClock() {
    return Clock.systemUTC();
  }
}