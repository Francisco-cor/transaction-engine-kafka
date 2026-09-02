package com.example.transactionengine.transaction.application;

import com.example.transactionengine.transaction.api.StatementResponse;
import com.example.transactionengine.transaction.persistence.StatementRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * CQRS read-model service with two-level cache (F6).
 * L1: Caffeine local 1s (hot-account mitigation).
 * L2: Redis 1s (cross-instance sharing).
 * DB fallback is StatementRepository (ledger_entries + accounts).
 */
@Service
public class StatementService {

  private static final Logger log = LoggerFactory.getLogger(StatementService.class);
  private static final String REDIS_KEY_PREFIX = "statement:";

  private final StatementRepository repository;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redisTemplate;
  private final Cache<String, StatementResponse> localCache;
  private final Duration redisTtl;

  public StatementService(
      StatementRepository repository,
      ObjectMapper objectMapper,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          StringRedisTemplate redisTemplate,
      @Value("${statement.cache.local-ttl-ms:1000}") long localTtlMs,
      @Value("${statement.cache.redis-ttl-ms:1000}") long redisTtlMs) {
    this.repository = repository;
    this.objectMapper = objectMapper.findAndRegisterModules();
    this.redisTemplate = redisTemplate;
    this.redisTtl = Duration.ofMillis(redisTtlMs);
    this.localCache =
        Caffeine.newBuilder()
            .expireAfterWrite(localTtlMs, TimeUnit.MILLISECONDS)
            .maximumSize(10_000)
            .recordStats()
            .build();
  }

  public StatementResponse getStatement(String accountId, int limit) {
    String cacheKey = accountId + ":" + limit;
    var cachedLocal = localCache.getIfPresent(cacheKey);
    if (cachedLocal != null) {
      return cachedLocal;
    }
    if (redisTemplate != null) {
      try {
        String redisKey = REDIS_KEY_PREFIX + cacheKey;
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json != null) {
          var fromRedis = objectMapper.readValue(json, StatementResponse.class);
          localCache.put(cacheKey, fromRedis);
          return fromRedis;
        }
      } catch (Exception exception) {
        log.warn("Redis statement cache miss/fallback: {}", exception.toString());
      }
    }
    var fromDb = repository.getStatement(accountId, limit);
    localCache.put(cacheKey, fromDb);
    if (redisTemplate != null) {
      try {
        String redisKey = REDIS_KEY_PREFIX + cacheKey;
        String json = objectMapper.writeValueAsString(fromDb);
        redisTemplate.opsForValue().set(redisKey, json, redisTtl);
      } catch (JsonProcessingException exception) {
        log.warn("Could not cache statement in Redis", exception);
      } catch (Exception exception) {
        log.warn("Redis set failed, continuing with local only: {}", exception.toString());
      }
    }
    return fromDb;
  }

  public void evict(String accountId) {
    // Evict all limits for account (best-effort); Caffeine keys are accountId:limit
    localCache.asMap().keySet().removeIf(key -> key.startsWith(accountId + ":"));
    if (redisTemplate != null) {
      try {
        var keys = redisTemplate.keys(REDIS_KEY_PREFIX + accountId + ":*");
        if (keys != null && !keys.isEmpty()) {
          redisTemplate.delete(keys);
        }
      } catch (Exception exception) {
        log.warn("Redis evict failed: {}", exception.toString());
      }
    }
  }
}
