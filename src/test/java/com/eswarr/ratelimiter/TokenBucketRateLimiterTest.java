package com.eswarr.ratelimiter;

import com.eswarr.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.eswarr.ratelimiter.model.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TokenBucketRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.reset("test-client");
    }

    @Test
    @DisplayName("Should ALLOW requests within the limit")
    void shouldAllowRequestsWithinLimit() {
        RateLimitResult result = rateLimiter.isAllowed("test-client");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getAlgorithm()).isEqualTo("TOKEN_BUCKET");
        assertThat(result.getRemainingTokens()).isLessThan(10);
    }

    @Test
    @DisplayName("Should DENY requests exceeding the limit")
    void shouldDenyRequestsExceedingLimit() {
        // Exhaust all 10 tokens
        for (int i = 0; i < 10; i++) {
            rateLimiter.isAllowed("test-client");
        }

        RateLimitResult result = rateLimiter.isAllowed("test-client");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemainingTokens()).isEqualTo(0L);
        assertThat(result.getRetryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("Different clients should have independent buckets")
    void differentClientsShouldHaveIndependentBuckets() {
        // Exhaust alice's tokens
        for (int i = 0; i < 10; i++) {
            rateLimiter.isAllowed("alice");
        }

        // Bob should still be allowed
        RateLimitResult bobResult = rateLimiter.isAllowed("bob");
        assertThat(bobResult.isAllowed()).isTrue();

        // Alice should be denied
        RateLimitResult aliceResult = rateLimiter.isAllowed("alice");
        assertThat(aliceResult.isAllowed()).isFalse();

        // Cleanup
        rateLimiter.reset("alice");
        rateLimiter.reset("bob");
    }

    @Test
    @DisplayName("Result should include all required metadata")
    void resultShouldIncludeAllRequiredMetadata() {
        RateLimitResult result = rateLimiter.isAllowed("test-client");

        assertThat(result.getClientId()).isEqualTo("test-client");
        assertThat(result.getAlgorithm()).isNotBlank();
        assertThat(result.getLimit()).isPositive();
        assertThat(result.getResetAt()).isPositive();
    }
}
