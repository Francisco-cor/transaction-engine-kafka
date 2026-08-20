package com.example.transactionengine.fraud;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FraudServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(FraudServiceApplication.class, args);
  }

  @Bean
  Clock utcClock() {
    return Clock.systemUTC();
  }
}
