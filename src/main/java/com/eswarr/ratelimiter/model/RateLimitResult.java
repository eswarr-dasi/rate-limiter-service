package com.eswarr.ratelimiter.model;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * DTO representing the result of a rate limit check.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitResult {
    private String clientId;
    private String algorithm;
    private boolean allowed;
    private Long remainingTokens;
    private Long limit;
    private Long resetAt;
    private Long retryAfterSeconds;
    // For /compare endpoint
    private RateLimitResult tokenBucket;
    private RateLimitResult slidingWindow;
}
