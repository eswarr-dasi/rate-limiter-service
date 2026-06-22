package com.eswarr.ratelimiter.service;

import com.eswarr.ratelimiter.algorithm.SlidingWindowRateLimiter;
import com.eswarr.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.eswarr.ratelimiter.model.RateLimitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Core service orchestrating rate limit checks.
 * Delegates to algorithm implementations and adds cross-cutting concerns.
 */
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final SlidingWindowRateLimiter slidingWindowRateLimiter;

    public RateLimitResult checkTokenBucket(String clientId) {
        return tokenBucketRateLimiter.isAllowed(clientId);
    }

    public RateLimitResult checkSlidingWindow(String clientId) {
        return slidingWindowRateLimiter.isAllowed(clientId);
    }

    /**
     * Run both algorithms and return a comparison result.
     * Useful for demos, testing, and algorithm evaluation.
     */
    public RateLimitResult compare(String clientId) {
        RateLimitResult tokenResult = tokenBucketRateLimiter.isAllowed(clientId);
        RateLimitResult slidingResult = slidingWindowRateLimiter.isAllowed(clientId);
        return RateLimitResult.builder()
                .clientId(clientId)
                .algorithm("COMPARISON")
                .allowed(tokenResult.isAllowed() && slidingResult.isAllowed())
                .tokenBucket(tokenResult)
                .slidingWindow(slidingResult)
                .build();
    }

    public void resetAll(String clientId) {
        tokenBucketRateLimiter.reset(clientId);
        slidingWindowRateLimiter.reset(clientId);
    }
}
