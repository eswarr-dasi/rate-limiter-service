package com.eswarr.ratelimiter.algorithm;
import com.eswarr.ratelimiter.model.RateLimitResult;

/**
 * Strategy interface for rate limiting algorithms.
 * Implementations must be thread-safe and support distributed deployments.
 */
public interface RateLimitAlgorithm {
    /**
     * Check whether the given client is within their rate limit.
     * @param clientId unique identifier (user ID, IP, API key)
     * @return RateLimitResult with allow/deny decision and metadata
     */
    RateLimitResult isAllowed(String clientId);

    /**
     * Reset the rate limit state for a client (useful for testing).
     * @param clientId the client to reset
     */
    void reset(String clientId);
}
