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
 * Sliding Window Log Rate Limiting Algorithm.
 *
 * Stores request timestamps in Redis ZSET. On each request:
 * 1. Remove timestamps older than (now - windowSeconds)
 * 2. Count remaining entries
 * 3. count < limit: add timestamp + ALLOW
 * 4. count >= limit: DENY with retry-after
 *
 * More precise than Fixed Window (no boundary burst artifacts).
 *
 * Time Complexity:  O(log N) per request
 * Space Complexity: O(requests-per-window) per client
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimiter implements RateLimitAlgorithm {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limiter.sliding.limit:10}")
    private long limit;

    @Value("${rate.limiter.sliding.window-seconds:60}")
    private long windowSeconds;

    private static final String KEY_PREFIX = "rate:sliding:";

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_start = tonumber(ARGV[2])
            local lim = tonumber(ARGV[3])
            local window_secs = tonumber(ARGV[4])
            local member = ARGV[5]
            redis.call('zremrangebyscore', key, '-inf', window_start)
            local count = redis.call('zcard', key)
            if count < lim then
                redis.call('zadd', key, now, member)
                redis.call('expire', key, window_secs * 2)
                return {1, count + 1, 0}
            else
                local oldest = redis.call('zrange', key, 0, 0, 'WITHSCORES')
                local retry = 0
                if oldest and #oldest >= 2 then
                    local oldest_ts = tonumber(oldest[2])
                    retry = math.ceil((oldest_ts / 1000 + window_secs) - now / 1000)
                end
                return {0, count, math.max(1, retry)}
            end
            """;

    @Override
    public RateLimitResult isAllowed(String clientId) {
        String key = KEY_PREFIX + clientId;
        long nowMs = Instant.now().toEpochMilli();
        long windowStartMs = nowMs - (windowSeconds * 1000);
        String member = nowMs + "-" + Thread.currentThread().getId();

        DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
        List<Long> result = redisTemplate.execute(
                script, List.of(key),
                String.valueOf(nowMs), String.valueOf(windowStartMs),
                String.valueOf(limit), String.valueOf(windowSeconds), member
        );

        boolean allowed = result != null && result.get(0) == 1L;
        long count = result != null ? result.get(1) : 0L;
        long retryAfter = result != null ? result.get(2) : windowSeconds;

        log.debug("SlidingWindow: clientId={}, allowed={}, count={}/{}", clientId, allowed, count, limit);

        return RateLimitResult.builder()
                .clientId(clientId)
                .algorithm("SLIDING_WINDOW")
                .allowed(allowed)
                .remainingTokens(allowed ? limit - count : 0L)
                .limit(limit)
                .resetAt(Instant.now().getEpochSecond() + windowSeconds)
                .retryAfterSeconds(allowed ? null : retryAfter)
                .build();
    }

    @Override
    public void reset(String clientId) {
        redisTemplate.delete(KEY_PREFIX + clientId);
    }
}
