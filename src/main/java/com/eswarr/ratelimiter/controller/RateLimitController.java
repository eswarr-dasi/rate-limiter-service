package com.eswarr.ratelimiter.controller;

import com.eswarr.ratelimiter.model.RateLimitResult;
import com.eswarr.ratelimiter.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing rate limit check endpoints.
 *
 * Standard rate-limit response headers are added to every response:
 *   X-RateLimit-Limit:     max requests allowed
 *   X-RateLimit-Remaining: requests remaining in window
 *   X-RateLimit-Reset:     UNIX timestamp of window reset
 *   Retry-After:           seconds to wait (only on 429)
 */
@RestController
@RequestMapping("/api/v1/ratelimit")
@RequiredArgsConstructor
public class RateLimitController {

    private final RateLimiterService rateLimiterService;

    /**
     * Check rate limit using Token Bucket algorithm.
     * GET /api/v1/ratelimit/token/{clientId}
     */
    @GetMapping("/token/{clientId}")
    public ResponseEntity<RateLimitResult> checkTokenBucket(@PathVariable String clientId) {
        RateLimitResult result = rateLimiterService.checkTokenBucket(clientId);
        return buildResponse(result);
    }

    /**
     * Check rate limit using Sliding Window algorithm.
     * GET /api/v1/ratelimit/sliding/{clientId}
     */
    @GetMapping("/sliding/{clientId}")
    public ResponseEntity<RateLimitResult> checkSlidingWindow(@PathVariable String clientId) {
        RateLimitResult result = rateLimiterService.checkSlidingWindow(clientId);
        return buildResponse(result);
    }

    /**
     * Compare both algorithms side-by-side.
     * GET /api/v1/ratelimit/compare/{clientId}
     */
    @GetMapping("/compare/{clientId}")
    public ResponseEntity<RateLimitResult> compare(@PathVariable String clientId) {
        RateLimitResult result = rateLimiterService.compare(clientId);
        return ResponseEntity.ok(result);
    }

    /**
     * Reset rate limit state for a client (for testing).
     * DELETE /api/v1/ratelimit/{clientId}
     */
    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> reset(@PathVariable String clientId) {
        rateLimiterService.resetAll(clientId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<RateLimitResult> buildResponse(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        if (result.getLimit() != null) {
            headers.add("X-RateLimit-Limit", String.valueOf(result.getLimit()));
        }
        if (result.getRemainingTokens() != null) {
            headers.add("X-RateLimit-Remaining", String.valueOf(result.getRemainingTokens()));
        }
        if (result.getResetAt() != null) {
            headers.add("X-RateLimit-Reset", String.valueOf(result.getResetAt()));
        }

        if (!result.isAllowed()) {
            if (result.getRetryAfterSeconds() != null) {
                headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(result.getRetryAfterSeconds()));
            }
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(result);
        }

        return ResponseEntity.ok().headers(headers).body(result);
    }
}
