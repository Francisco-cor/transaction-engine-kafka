package com.example.transactionengine.fraud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class FraudDecisionCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(FraudDecisionCache.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;

  public FraudDecisionCache(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @org.springframework.beans.factory.annotation.Value("${fraud.cache.ttl-seconds:300}")
          long ttlSeconds) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
  }

  public Optional<CachedDecision> get(UUID transactionId) {
    try {
      var raw = redis.opsForValue().get(key(transactionId));
      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(raw, CachedDecision.class));
    } catch (DataAccessException | JsonProcessingException exception) {
      LOGGER.warn("Fraud cache unavailable; evaluating from PostgreSQL", exception);
      return Optional.empty();
    }
  }

  public void put(UUID transactionId, CachedDecision decision) {
    try {
      redis.opsForValue().set(key(transactionId), objectMapper.writeValueAsString(decision), ttl);
    } catch (DataAccessException | JsonProcessingException exception) {
      LOGGER.warn("Fraud cache write failed; PostgreSQL remains authoritative", exception);
    }
  }

  private static String key(UUID transactionId) {
    return "fraud:decision:" + transactionId;
  }

  public record CachedDecision(String decision, String reasonCode, String ruleCode, int riskScore) {}
}
