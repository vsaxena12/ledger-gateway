# Event Ledger

Two Spring Boot 4.1.1 / Java 26 microservices that process financial
transaction events with idempotency, out-of-order tolerance, distributed
tracing, observability, and resiliency.

```
        ┌──────────────────────┐
Client │  event-gateway       │  :8080 (public)
   ────▶│  (Spring Boot)       │
        │  - H2 file           │──────────┐
        │  - /events           │          │ REST + traceparent
        │  - /health           │          │ Resilience4j decorators
        └──────────────────────┘          ▼
                                  ┌──────────────────────┐
                                  │  account-service     │  :8081 (internal)
                                  │  (Spring Boot)       │
                                  │  - H2 file           │
                                  │  - /accounts/...     │
                                  │  - /health           │
                                  └──────────────────────┘
```

## Architecture

- **event-gateway** (port 8080, public) — receives events via `POST /events`,
  validates input, enforces idempotency, persists the event locally, then calls
  the account-service to apply the transaction. On failure, events are queued
  locally for async retry.
- **account-service** (port 8081, internal) — manages account state (balances,
  transaction history). Applies CREDIT/DEBIT transactions and returns updated
  balances. Not exposed to external clients.

Each service has its own H2 file (PostgreSQL compatibility mode). No
shared database or in-process state. Communication is synchronous REST
with W3C `traceparent` header propagation.

## Quick start

### Prerequisites

- Java 26+
- Maven 3.9+

### Run with Maven

Terminal 1 — account service:
```bash
cd account-service && mvn spring-boot:run
```

Terminal 2 — gateway:
```bash
cd gateway && mvn spring-boot:run
```

### Run with Docker Compose (includes Jaeger trace visualization)

```bash
docker compose up --build
# Jaeger UI: http://localhost:16686
```

### Smoke test

```bash
# Submit a CREDIT event
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
       "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

# List events for account (chronological order)
curl http://localhost:8080/events?account=a1

# Check balance
curl http://localhost:8081/accounts/a1/balance

# Test idempotency (same eventId → 200, not duplicate)
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","accountId":"a1","type":"CREDIT","amount":999,
       "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'
# Returns 200 OK with original amount, balance unchanged
```

### View observability

```bash
# Health
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep gateway_events

# Structured JSON logs (both services)
tail -f /tmp/gw_e2e.log
```

## Tests

```bash
# All tests (20 total)
mvn test

# Coverage report (JaCoCo — Java 25 target for compatibility)
mvn -Pcoverage clean test
# Reports: gateway/target/site/jacoco-unit/index.html
#          account-service/target/site/jacoco-unit/index.html

# Contract tests (Pact — excluded from default mvn test)
mvn test -Pcontract-tests
```

| Module | Tests | Coverage (instruction) |
|--------|-------|-----------------------|
| gateway | 11 (5 service + 5 controller slice + 1 E2E) | 78.3% |
| account-service | 9 (5 service + 4 controller slice) | 67.5% |

## API

### Event Gateway (`POST /events`, `GET /events`, `GET /events/{id}`)

| Endpoint | Method | Description |
|---|---|---|
| `/events` | POST | Submit a transaction event (see payload below) |
| `/events/{id}` | GET | Retrieve a single event by eventId |
| `/events?account={accountId}` | GET | List events for an account, ordered by eventTimestamp |
| `/actuator/health` | GET | Health check with DB status |
| `/actuator/prometheus` | GET | Prometheus metrics |

**Event payload:**

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe", "batchId": "B-9042" }
}
```

**Status codes:**
- `201` — First-time submission, event applied
- `200` — Duplicate eventId (idempotent, balance unchanged)
- `400` — Validation failure (missing fields, zero/negative amount, invalid type)
- `503` — Account service unavailable

### Account Service (`POST /accounts/{id}/transactions`, `GET /accounts/{id}/balance`)

Internal API consumed by the gateway over `localhost:8081`.

## Resiliency

The gateway wraps the account-service call with a **Retry → Bulkhead → CircuitBreaker** decorator chain:

| Pattern | Setting | Rationale |
|---------|---------|-----------|
| **Circuit Breaker** | 50% failure rate, 10-call sliding window, 10s open state | Prevents cascading failures when account-service is degraded |
| **Bulkhead** | `SemaphoreBulkhead(maxConcurrentCalls=20)` | Semaphore over thread-pool: Java 26 virtual threads make thread-pool exhaustion irrelevant |
| **Retry** | 3 attempts, exponential backoff 100→200→400ms + ±50% jitter | Transient network issues self-heal |
| **Rate Limiter** | 50 requests/second | Bonus: prevents traffic spikes from overwhelming downstream |
| **Async Fallback** | Event persisted locally with `appliedToAccount=false`; `@Scheduled` retry every 30s (max 5) | Bonus: no event loss during outages |

## Graceful degradation

When account-service is unavailable:

- `POST /events` — returns `201`. Event persists locally with `appliedToAccount=false`;
  async retry applies it when the service recovers.
- `GET /events/{id}` and `GET /events?account=...` — always work (local read only).
- Balance queries — depend on account-service availability.

## Tracing

Custom trace propagation using the W3C `traceparent` header format:

1. **Gateway** generates or forwards a trace ID on each incoming request
2. Trace ID is propagated to account-service via the `traceparent` HTTP header
3. Both services log `traceId` and `spanId` in every JSON log entry
4. Response header `X-Trace-Id` contains the trace ID for client-side correlation

The same trace ID appears in both services' logs for a single client request,
enabling end-to-end traceability.

When running with Docker Compose, traces are also forwarded to the
OpenTelemetry Collector → Jaeger for visualization:

```
Event Gateway ──OTLP──▶ OTel Collector ──▶ Jaeger UI (:16686)
Account Svc   ──OTLP──▶ OTel Collector ──▶ Jaeger UI (:16686)
```

## Auditing

Every event is recorded as an immutable audit log entry:

- `event_id` — unique identifier (enforces idempotency)
- `account_id`, `type`, `amount`, `currency` — transaction details
- `event_timestamp`, `received_at` — temporal audit trail
- `metadata` — optional context stored as JSON (e.g., source system, batch ID)
- `applied_to_account`, `retry_count`, `last_retry_at` — lifecycle tracking
- `traceId` — correlates events across services in structured logs

Events can be queried via `GET /events?account={accountId}` in chronological
order for full account activity history.

## Observability

| Feature | Endpoint / Mechanism |
|---------|---------------------|
| Structured JSON logs | Console — includes `service`, `traceId`, `spanId`, `level`, `timestamp` |
| Metrics | `GET /actuator/prometheus` — custom: `gateway.events.received`, `gateway.account_service.latency` |
| Health | `GET /actuator/health` — includes DB connectivity, disk space |
| Jaeger traces | Available via Docker Compose at `http://localhost:16686` |

## Design decisions

- **Virtual threads**: Spring Boot 4.1 enables virtual threads by default
  (`spring.threads.virtual.enabled=true`). Tomcat request handling, RestClient
  calls, and Resilience4j all run on virtual threads.
- **DB-level idempotency**: `UNIQUE(event_id)` constraints with
  `DuplicateKeyException` handling — simpler and more reliable than
  application-level dedup.
- **Balance computation**: Sum of CREDITs minus DEBITs at read time.
  Commutative — arrival order doesn't affect correctness.
- **H2 PostgreSQL mode**: Embedded, zero-config, but supports real SQL
  constraints (CHECK, UNIQUE, FK) and JSON columns.

## Tech stack

| Component | Choice |
|-----------|--------|
| Language | Java 26 |
| Framework | Spring Boot 4.1.1 (SNAPSHOT) |
| Build | Maven 3.9+, multi-module |
| Database | H2 (PostgreSQL compatibility mode, per-service file) |
| Resiliency | Resilience4j (CircuitBreaker, Retry, Bulkhead, RateLimiter) |
| Tracing | Custom W3C traceparent + OpenTelemetry Collector + Jaeger (Docker) |
| Metrics | Micrometer + Prometheus |
| Logging | Logback + logstash-logback-encoder (JSON) |
| Tests | JUnit 5 + Mockito + Spring Boot Test + Pact (contracts) |
| Docker | Cloud Native Buildpacks / multi-stage Dockerfiles |
