package com.eswarr.ratelimiter.algorithm;

import com.eswarr.ratelimiter.model.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Token Bucket Rate Limiting Algorithm.
 *
 * Each client has a bucket with capacity N tokens.
 * Tokens refill at a fixed rate. Each request consumes 1 token.
 * Empty bucket = HTTP 429 Too Many Requests.
 *
 * Uses atomic Lua script in Redis to prevent race conditions
 * across multiple service instances.
 *
 * Time Complexity:  O(1) per request
 * Space Complexity: O(1) per client (two Redis keys)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimiter implements RateLimitAlgorithm {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limiter.token.capacity:10}")
    private long capacity;

    @Value("${rate.limiter.token.refill-rate:10}")
    private long refillRate;

    @Value("${rate.limiter.token.refill-duration-seconds:60}")
    private long refillDurationSeconds;

    private static final String KEY_PREFIX = "rate:token:";

    // Atomic Lua: check tokens, refill based on time elapsed, allow/deny
    private static final String LUA_SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local refill_duration = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local last_tokens = tonumber(redis.call('get', tokens_key))
            local last_refreshed = tonumber(redis.call('get', timestamp_key))
            if last_tokens == nil then last_tokens = capacity end
            if last_refreshed == nil then last_refreshed = now end
            local delta = math.max(0, now - last_refreshed)
            local refill_per_ms = refill_rate / (refill_duration * 1000)
            local filled = math.min(capacity, last_tokens + (delta * refill_per_ms))
            local allowed = filled >= 1
            local new_tokens = filled
            if allowed then new_tokens = filled - 1 end
            redis.call('setex', tokens_key, refill_duration * 2, new_tokens)
            redis.call('setex', timestamp_key, refill_duration * 2, now)
            local ms_per_token = 1 / refill_per_ms
            local reset_at = math.ceil(now / 1000 + (capacity - new_tokens) * ms_per_token / 1000)
            if allowed then return {1, math.floor(new_tokens), reset_at}
            else return {0, 0, reset_at} end
            """;

    @Override
    public RateLimitResult isAllowed(String clientId) {
        String tokensKey = KEY_PREFIX + clientId + ":tokens";
        String timestampKey = KEY_PREFIX + clientId + ":ts";
        long nowMillis = Instant.now().toEpochMilli();

        DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
        List<Long> result = redisTemplate.execute(
                script,
                List.of(tokensKey, timestampKey),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(refillDurationSeconds),
                String.valueOf(nowMillis)
        );

        boolean allowed = result != null && result.get(0) == 1L;
        long remaining = result != null ? result.get(1) : 0L;
        long resetAt = result != null ? result.get(2) : nowMillis / 1000 + refillDurationSeconds;

        log.debug("TokenBucket: clientId={}, allowed={}, remaining={}", clientId, allowed, remaining);

        return RateLimitResult.builder()
                .clientId(clientId)
                .algorithm("TOKEN_BUCKET")
                .allowed(allowed)
                .remainingTokens(remaining)
                .limit(capacity)
                .resetAt(resetAt)
                .retryAfterSeconds(allowed ? null : resetAt - Instant.now().getEpochSecond())
                .build();
    }

    @Override
    public void reset(String clientId) {
        redisTemplate.delete(KEY_PREFIX + clientId + ":tokens");
        redisTemplate.delete(KEY_PREFIX + clientId + ":ts");
    }
}
