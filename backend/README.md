# 🔗 ShortUrl — High-Throughput URL Shortening Service

A production-grade, distributed URL shortening service built with **Java 21**, **Spring Boot 3.5**, **Redis**, and **PostgreSQL**. Engineered for high-concurrency read and write throughput with sub-11ms p95 latency under load.

---

## ⚡ Key Optimizations & Architecture

### 1. Sub-11ms Redirects via Redis L1 Cache
- Redirects (`GET /s/public/{shortKey}`) resolve directly from Redis memory via `CachedShortUrlService`.
- Database reads are completely bypassed on cache hits, protecting PostgreSQL from read spikes.
- Cache entries are automatically evicted on URL updates, deletions, or expirations.

### 2. Write-Behind Click Buffering (Zero DB Lock Contention)
- In traditional shorteners, updating click counts on every redirect causes severe database row-level locking.
- **Our Solution:** Redirects execute an atomic Redis memory increment (`redissonClient.getAtomicLong("clicks:" + id).incrementAndGet()`).
- A background worker (`ClickCountFlusher`) wakes up every 30 seconds to batch-flush accumulated counts into PostgreSQL in a single bulk update.

### 3. Distributed Token-Bucket Rate Limiting
- Custom `RateLimitFilter` powered by Redisson distributed token buckets protects all endpoints:
  - **Redirects:** 30 requests/minute per IP
  - **URL Creation:** 10 requests/minute per user
  - **Auth:** 3 requests/minute per IP
- **Auto-Expiring Keys:** Applies TTLs on bucket creation to prevent unbounded Redis memory growth.
- **Fast Fail:** Abusive requests are rejected at the servlet filter boundary in **~3ms** without consuming application or database threads.

### 4. Collision-Resilient Key Generation (Insert-and-Retry)
- Generates 6-character Base62 keys ($62^6 \approx 56.8\text{ billion}$ combinations).
- Eliminates `SELECT`-before-`INSERT` race conditions: writes optimistically and catches `DataIntegrityViolationException` with exponential backoff retries.

### 5. Automated Expiry & Soft Deletions
- Configurable per-URL expiration.
- Scheduled cleanup service (`ShortUrlCleanupService`) marks expired URLs and evicts their Redis cache entries.
- User-initiated deletions use soft-deletion with bulk ownership validation.

---

## 📊 Performance & Benchmarks (k6 Verified)

Tested locally under containerized conditions using **k6** simulating 50 concurrent virtual users hitting public redirect endpoints:

| Metric | Benchmark Result | Target SLA | Status |
|---|---|---|---|
| **Peak Throughput** | **260.4 requests/sec** | > 100 req/s | ✅ PASSED |
| **Success Rate** | **100.00%** (6,514 / 6,514) | > 99% | ✅ PASSED |
| **Error Rate** | **0.00%** | < 1% | ✅ PASSED |
| **p95 Latency** | **10.94 ms** | < 30 ms | ✅ PASSED |
| **Median Latency** | **4.53 ms** | < 10 ms | ✅ PASSED |
| **Minimum Latency** | **1.29 ms** | — | — |
| **Rate Limit Rejection** | **~3.22 ms** | < 10 ms | ✅ PASSED |

```
  █ THRESHOLDS
    ✓ 'p(95)<30' p(95)=10.94ms
    ✓ 'rate<0.01' rate=0.00%
```

---

## 📡 API Overview

### Public & Redirect Endpoints
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/s/public/{shortKey}` | Resolve public short URL (returns `302 Found` with `Location` header) |
| `GET` | `/s/{shortKey}` | Resolve private short URL (requires Bearer token; returns `200 OK`) |

### URL Management (`/api/v1/shorten`)
| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/` | User | Create short URL (custom expiry, private/public flag) |
| `GET` | `/` | User | List user URLs with pagination, search, and sorting |
| `PUT` | `/{shortKey}` | User | Update original destination URL |
| `DELETE` | `/bulk` | User | Bulk soft-delete short URLs |

### Authentication (`/api/v1/auth`)
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/signup` | Register new user account |
| `POST` | `/login` | Authenticate, returns short-lived JWT + refresh token |
| `POST` | `/refreshToken` | Issue new access token using refresh token rotation |
| `POST` | `/forgot-password` | Generate reset token and dispatch email via Brevo |
| `POST` | `/reset-password` | Reset account password using verified token |

### Admin & Observability
| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/v1/admin/users` | Admin | Paginated user management with URL counts |
| `GET` | `/api/v1/admin/stats` | Admin | Total platform URLs, active links, total clicks |
| `GET` | `/actuator/health` | Public | Liveness and readiness health checks |
| `GET` | `/actuator/prometheus` | Public | Micrometer metrics for Prometheus scraping |

---

## 🛠️ Tech Stack

- **Runtime:** Java 21, Spring Boot 3.5
- **Persistence:** PostgreSQL 16, Spring Data JPA, Hibernate 6
- **Database Migrations:** Flyway 11 (`ddl-auto=validate`)
- **Caching & Limiting:** Redis 7, Redisson 3.27, Spring Cache
- **Security:** Spring Security, JJWT (HMAC-SHA256), BCrypt
- **Email Service:** Brevo (Sendinblue) REST API
- **Testing:** JUnit 5, Mockito, AssertJ, Testcontainers, k6
- **Containerization:** Docker (multi-stage build), Docker Compose

---

## 🚀 Quick Start (Docker)

### 1. Clone & Run with Docker Compose
```bash
cd shortUrl

# Start PostgreSQL, Redis, and Spring Boot App
docker compose up -d --build
```

### 2. Verify Deployment
- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health Check: `http://localhost:8080/actuator/health`

### 3. Rebuild After Code Changes
```bash
docker build -t short-url-app .
docker compose up -d --no-deps --force-recreate app
```

---

## 🧪 Testing

### Automated Test Suite (Unit & Integration)
```bash
# Run unit tests only (fast, no Docker needed)
./mvnw test

# Run full suite including Testcontainers (PostgreSQL + Redis)
./mvnw clean verify
```

### k6 Load Testing Suite
Load test scripts live in the `load-tests/` directory:
```bash
# 1. Rate Limiter Validation Test
docker run --rm -i --network shorturl_default -v "${PWD}/load-tests:/scripts" grafana/k6 run /scripts/rate-limit-test.js

# 2. High-Throughput Concurrency Benchmark
docker run --rm -i --network shorturl_default -v "${PWD}/load-tests:/scripts" grafana/k6 run /scripts/redirect-benchmark.js
```

---

## ⚙️ Environment Configuration

Set these in your `docker-compose.yml` or `application.properties`:

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://postgres:5432/shorturl_db` |
| `DB_USERNAME` | Database username | `appuser` |
| `DB_PASSWORD` | Database password | `apppass` |
| `REDIS_HOST` | Redis host | `redis` |
| `REDIS_PORT` | Redis port | `6379` |
| `JWT_SECRET` | 256-bit Base64 secret key for signing JWTs | — |
| `BREVO_API_KEY` | API key for transactional emails | — |
| `FRONTEND_URL` | Allowed CORS origin | `http://localhost:5173` |
