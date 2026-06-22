# rate-limiter-service

> Production-grade **Distributed Rate Limiter** built with Java 17, Spring Boot 3, and Redis.
> A classic FAANG system design problem — implemented end-to-end with two algorithms, REST API, Docker support, and full test coverage.

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?style=flat-square&logo=spring-boot)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7.x-DC382D?style=flat-square&logo=redis)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?style=flat-square&logo=docker)](https://docker.com/)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)

---

## What Is This?

A **rate limiter** controls how many requests a client can make to an API within a time window. It's a critical component in any high-scale system — protecting backends from abuse, enforcing fair usage, and maintaining SLA guarantees.

This service implements two battle-tested algorithms:

| Algorithm | Best For | Storage Complexity |
|-----------|----------|-------------------|
| **Token Bucket** | Bursty traffic with a sustained rate cap | O(1) per key |
| **Sliding Window Log** | Precise per-second enforcement | O(n) per key |

Both are backed by **Redis** with atomic Lua scripts — ensuring correctness even across multiple service instances.

---

## System Design

```
                        ┌──────────────────────────────────────┐
                        │         Rate Limiter Service          │
                        │                                       │
  Client Request  ──►   │  ┌─────────────┐  ┌───────────────┐  │
                        │  │  REST API   │  │  Algorithm    │  │
                        │  │  Controller │─►│  Strategy     │  │
                        │  └─────────────┘  │  (Token Bucket│  │
                        │         │         │  / Sliding Win)│  │
                        │         ▼         └───────┬───────┘  │
                        │  ┌─────────────┐          │          │
                        │  │Rate Limit   │          │          │
                        │  │ Response    │          ▼          │
                        │  │(200/429)    │  ┌───────────────┐  │
                        │  └─────────────┘  │  Redis Store  │  │
                        │                   │  (Atomic Lua) │  │
                        │                   └───────────────┘  │
                        └──────────────────────────────────────┘

  Headers returned:
    X-RateLimit-Limit:     10          (max requests allowed)
    X-RateLimit-Remaining: 7           (requests left in window)
    X-RateLimit-Reset:     1719000060  (UNIX timestamp of reset)
    Retry-After:           30          (seconds until retry, if 429)
```

---

## Project Structure

```
rate-limiter-service/
├── src/
│   └── main/
│       └── java/com/eswarr/ratelimiter/
│           ├── RateLimiterApplication.java         # Spring Boot entry point
│           ├── algorithm/
│           │   ├── RateLimitAlgorithm.java         # Strategy interface
│           │   ├── TokenBucketRateLimiter.java     # Token Bucket impl
│           │   └── SlidingWindowRateLimiter.java   # Sliding Window impl
│           ├── config/
│           │   └── RedisConfig.java                # Redis + Lua script setup
│           ├── controller/
│           │   └── RateLimitController.java        # REST endpoints
│           ├── model/
│           │   └── RateLimitResult.java            # Result DTO
│           └── service/
│               └── RateLimiterService.java         # Core service
│   └── test/
│       └── java/com/eswarr/ratelimiter/
│           ├── TokenBucketRateLimiterTest.java
│           └── SlidingWindowRateLimiterTest.java
├── docker-compose.yml
├── Dockerfile
├── pom.xml
└── README.md
```

---

## Algorithms Deep Dive

### Token Bucket

Each client gets a "bucket" with a max capacity of N tokens. Tokens refill at a fixed rate (e.g., 10/min). Each request consumes 1 token. If the bucket is empty → **429 Too Many Requests**.

```
Bucket capacity: 10 tokens
Refill rate:     10 tokens/minute
Current tokens:  7

Request comes in → consume 1 token → tokens = 6 → ✅ ALLOWED
...10 more requests → tokens = 0 → ❌ RATE LIMITED (429)
After 6 seconds → tokens refilled → ✅ ALLOWED again
```

**Redis key:** `rate:token:{clientId}`
**Lua script:** Atomic check-and-decrement to avoid race conditions across instances.

### Sliding Window Log

Stores timestamps of every request in a Redis sorted set. On each request, removes entries older than the window, counts remaining entries, and allows/denies based on the limit.

```
Window: 60 seconds, Limit: 10 requests

Sorted set for client "user-123":
  { 1719000001, 1719000015, 1719000032, ..., 1719000055 }  ← 10 entries

New request at 1719000060:
  → Remove entries < (1719000060 - 60) = 1719000000
  → Count remaining = 10 → ❌ RATE LIMITED (429)
  → Count remaining = 9  → ✅ ALLOWED, add timestamp
```

---

## API Reference

### Check Rate Limit (Token Bucket)

```http
GET /api/v1/ratelimit/token/{clientId}
```

**Response 200 OK** (allowed):
```json
{
  "clientId": "user-123",
  "algorithm": "TOKEN_BUCKET",
  "allowed": true,
  "remainingTokens": 7,
  "limit": 10,
  "resetAt": 1719000060
}
```

**Response 429 Too Many Requests** (blocked):
```json
{
  "clientId": "user-123",
  "algorithm": "TOKEN_BUCKET",
  "allowed": false,
  "remainingTokens": 0,
  "retryAfterSeconds": 12
}
```

---

### Check Rate Limit (Sliding Window)

```http
GET /api/v1/ratelimit/sliding/{clientId}
```

---

### Compare Both Algorithms

```http
GET /api/v1/ratelimit/compare/{clientId}
```

Returns results from both algorithms side-by-side — great for testing and demos.

---

### Health Check

```http
GET /actuator/health
```

---

## Quick Start

### With Docker Compose (recommended)

```bash
git clone https://github.com/eswarr-dasi/rate-limiter-service.git
cd rate-limiter-service
docker-compose up --build
```

Service starts at `http://localhost:8080`

### Local Development

**Prerequisites:** Java 17, Maven 3.8+, Redis 7.x running on localhost:6379

```bash
# Start Redis
redis-server

# Build and run
./mvnw spring-boot:run
```

### Test the API

```bash
# Make 15 requests as "alice" — first 10 succeed, then 429
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:8080/api/v1/ratelimit/token/alice
done
# Output: 200 200 200 200 200 200 200 200 200 200 429 429 429 429 429

# Compare algorithms
curl http://localhost:8080/api/v1/ratelimit/compare/alice | jq .
```

---

## Configuration

`application.properties`:

```properties
# Token Bucket
rate.limiter.token.capacity=10
rate.limiter.token.refill-rate=10
rate.limiter.token.refill-duration-seconds=60

# Sliding Window
rate.limiter.sliding.limit=10
rate.limiter.sliding.window-seconds=60

# Redis
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

---

## Key Design Decisions

**Why Redis?**
- Atomic operations via Lua scripts prevent race conditions across multiple service instances
- Sub-millisecond latency — adds < 1ms to request processing
- TTL support — keys automatically expire, no cleanup needed

**Why Lua scripts?**
- Redis executes Lua atomically — no other commands can interleave
- Eliminates the check-then-act race condition that would exist with separate GET/SET commands
- Critical for correctness in distributed deployments

**Why Strategy Pattern?**
- New algorithms (Fixed Window, Leaky Bucket) can be added without changing existing code
- Open/Closed Principle — open for extension, closed for modification

---

## Running Tests

```bash
./mvnw test
```

Tests use an embedded Redis (Testcontainers) — no external dependencies required.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Cache / State | Redis 7.x |
| Scripting | Lua (atomic Redis ops) |
| Build | Maven |
| Containerization | Docker + Docker Compose |
| Testing | JUnit 5, Mockito, Testcontainers |
| Observability | Spring Actuator |

---

## Interview Talking Points

This project demonstrates mastery of several FAANG-level system design concepts:

- **Distributed coordination** — atomic Lua scripts solve the race condition problem
- **Algorithm tradeoffs** — Token Bucket allows bursts; Sliding Window is more precise
- **Horizontal scalability** — Redis as shared state means any instance can serve any request
- **Observability** — response headers give clients actionable rate limit information
- **Extensibility** — Strategy pattern makes adding new algorithms trivial

> *"Given the Redis TTL and atomic Lua approach, this scales to millions of clients with O(1) memory per client for Token Bucket, or O(requests-per-window) for Sliding Window."*

---

## Author

**Eswarr Dasi** · [eswarr-dasi.github.io](https://eswarr-dasi.github.io) · [LinkedIn](https://linkedin.com/in/eswarr-dasi)
