package com.example.transactionengine.ledger.sharding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consistent hashing resolver for hot-account sharding (F6).
 * Maps accountId to 0..shardCount-1 via floorMod(hash, shardCount).
 * Default 32 shards balances hot-account contention vs DB overhead.
 * Used for metrics tagging and future physical sharding (pg partitions).
 */
@Component
public class AccountShardResolver {

  private final int shardCount;

  public AccountShardResolver(
      @Value("${ledger.sharding.shard-count:32}") int shardCount) {
    if (shardCount <= 0 || shardCount > 128) {
      throw new IllegalArgumentException("shardCount must be 1..128");
    }
    this.shardCount = shardCount;
  }

  public int resolve(String accountId) {
    if (accountId == null) {
      return 0;
    }
    // Use Murmur-like mixing to spread sequential ids; fallback to hashCode with spread
    int h = accountId.hashCode();
    h ^= (h >>> 16);
    return Math.floorMod(h, shardCount);
  }

  public int shardCount() {
    return shardCount;
  }

  public String shardTag(String accountId) {
    return String.valueOf(resolve(accountId));
  }
}
