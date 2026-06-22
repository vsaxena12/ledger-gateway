# Event Ledger — Design

Date: 2026-06-22
Owner: vsaxena12
Status: Approved (brainstorming complete)

## Goal

Build the Event Ledger take-home: two Spring Boot microservices that process
financial transaction events with idempotency, out-of-order tolerance,
distributed tracing, observability, and resiliency.

Stack: Java 26, Spring Boot 4.1.1 (SNAPSHOT), Maven multi-module, H2
(PostgreSQL mode, per-service persistent file), Resilience4j, OpenTelemetry,
Micrometer/Prometheus, Docker Compose.

## Architecture

```
        ┌──────────────────────┐
Browser │  event-gateway       │  port 8080 (public)
   ────▶│  (Spring Boot)       │
        │  - H2 file           │
        │  - /events           │──────────┐
        │  - /health           │          │ REST + traceparent
        │  - Resilience4j      │          ▼
        └──────────────────────┘  ┌──────────────────────┐
                                  │  account-service     │  port 8081 (internal)
                                  │  (Spring Boot)       │
                                  │  - H2 file           │
                                  │  - /accounts/...     │
                                  │  - /health           │
                                  └──────────────────────┘
```

Two independent processes, separate H2 files, no shared state.
Gateway is the only public surface; account-service is internal-only.

## Module layout

```
ledger-gateway/
├── pom.xml                         # parent BOM
├── docker-compose.yml              # gateway + account-service
├── README.md
├── docs/superpowers/specs/         # this file + later plans
├── .remember/                      # session memory
├── gateway/
│   ├── pom.xml
│   ├── Dockerfile                  # Cloud Native Buildpacks
│   └── src/...
└── account-service/
    ├── pom.xml
    ├── Dockerfile
    └── src/...
```

## Data model

### gateway.events
| Column | Type | Constraint |
|---|---|---|
| id | BIGINT | PK auto |
| event_id | VARCHAR(64) | UNIQUE NOT NULL |
| account_id | VARCHAR(64) | NOT NULL, indexed |
| type | VARCHAR(16) | NOT NULL, CHECK IN ('CREDIT','DEBIT') |
| amount | DECIMAL(19,4) | NOT NULL, CHECK > 0 |
| currency | VARCHAR(8) | NOT NULL |
| event_timestamp | TIMESTAMP WITH TIME ZONE | NOT NULL |
| metadata | JSON | nullable |
| received_at | TIMESTAMP WITH TIME ZONE | NOT NULL |
| applied_to_account | BOOLEAN | NOT NULL DEFAULT FALSE |

Index: `(account_id, event_timestamp)` for chronological listing.

### account-service.accounts
| Column | Type | Constraint |
|---|---|---|
| id | VARCHAR(64) | PK |
| created_at | TIMESTAMP WITH TIME ZONE | NOT NULL |

### account-service.transactions
| Column | Type | Constraint |
|---|---|---|
| id | BIGINT | PK auto |
| event_id | VARCHAR(64) | UNIQUE NOT NULL |
| account_id | VARCHAR(64) | FK → accounts(id) |
| type | VARCHAR(16) | NOT NULL, CHECK IN ('CREDIT','DEBIT') |
| amount | DECIMAL(19,4) | NOT NULL, CHECK > 0 |
| applied_at | TIMESTAMP WITH TIME ZONE | NOT NULL |

Index: `(account_id, applied_at)`.

UNIQUE on `event_id` gives DB-level idempotency in both services.

## API contracts

### Gateway (`/events`)

**POST /events** — submit event
- Body: as defined in handout.
- Behavior: validate → INSERT (catches UNIQUE violation) → call account-service.
- 201 Created on first submission.
- 200 OK + original event on duplicate.
- 400 Bad Request on validation failure (missing field, type, amount ≤ 0).
- 503 Service Unavailable when account-service is down or circuit is open.
- Logs include traceId; increments `gateway.events.received{result="created|duplicate|rejected"}`.

**GET /events/{id}** — single event
- 200 OK + event body; 404 if not found.
- Local read only (works even when account-service is down).

**GET /events?account={accountId}** — list for account
- Ordered by `event_timestamp` ASC.
- Local read only (works when account-service is down).

**GET /health** — Spring Boot Actuator
- Always includes H2 indicator.

### Account service (`/accounts`)

**POST /accounts/{accountId}/transactions**
- Body: `{eventId, type, amount, currency, metadata?}`.
- Behavior: ensure account row exists (auto-create) → INSERT transaction (UNIQUE catches duplicates) → return updated balance.
- 200 OK with `{accountId, balance, transaction}` on success.
- 200 OK with existing transaction on duplicate (idempotent).
- 400 Bad Request on validation failure.

**GET /accounts/{accountId}/balance**
- Computed: `SUM(CASE type WHEN 'CREDIT' THEN amount ELSE -amount END)`.
- 200 OK + `{accountId, balance}`.
- 404 if account does not exist.

**GET /accounts/{accountId}**
- Account metadata + recent 50 transactions.
- 404 if missing.

**GET /health** — Actuator + H2 indicator.

## Cross-cutting

### Validation
Spring `@Valid` + Jakarta Bean Validation. Custom `EventValidator` enforces
amount > 0 and type ∈ {CREDIT, DEBIT}. Errors return 400 with a JSON list of
field errors.

### Out-of-order handling
- `GET /events?account=X` orders by `event_timestamp` — insertion order
  irrelevant.
- Balance = commutative sum of CREDITs − DEBITs — arrival order irrelevant.

### Idempotency
- Gateway: `INSERT ... ON event_id` — `DuplicateKeyException` → fetch +
  return original with 200.
- Account service: same on `transactions.event_id`.

### Resiliency (Resilience4j)
Decorator order: `Retry(Bulkhead(CircuitBreaker(RestClient)))`.

- **CircuitBreaker**: 50% failure rate over sliding window of 10 calls →
  opens for 10s. When open, POST /events returns 503. GET /events endpoints
  unaffected (no account-service call).
- **Retry**: 3 attempts, exponential backoff 100/200/400ms + ±50ms jitter.
  Only on connect errors and 5xx. Never on 4xx (validation failures).
- **Bulkhead**: `SemaphoreBulkhead(maxConcurrentCalls=20)`. Chosen over
  thread-pool bulkhead because Java 26 virtual threads make semaphore-based
  isolation sufficient (no thread pool to exhaust).

### Tracing (OpenTelemetry)
- `opentelemetry-spring-boot-starter` for auto-instrumentation.
- Trace ID generated by the gateway for every incoming request.
- Propagated to account-service via the standard `traceparent` header
  (W3C trace context) — HTTP client RestClient is auto-instrumented.
- Logback `opentelemetry-logback-appender-1.0` includes `traceId` and
  `spanId` in every JSON log line.
- Verification: `TracePropagationTest` asserts the gateway's traceId
  appears in the account-service logs for the same request.

### Observability
- **Logs**: Logback `logstash-logback-encoder` → JSON. Includes `service`,
  `traceId`, `spanId`, `level`, `timestamp`, `message`, MDC.
- **Metrics**: Micrometer with Prometheus registry at `/actuator/prometheus`.
  Custom: `gateway.events.received{result}`, `gateway.events.duplicate`,
  `gateway.account_service.calls{outcome}`, `gateway.account_service.latency`
  (Timer histogram), `resilience4j.circuitbreaker.state`.
- **Health**: Spring Boot Actuator with H2 indicator + Resilience4j health
  indicator exposed at `/actuator/health`.

### Virtual threads
Spring Boot 4.1 enables virtual threads by default
(`spring.threads.virtual.enabled=true` in application.yml). No code change
needed — Tomcat, RestClient, and Resilience4j all run on the
`VirtualThreadTaskExecutor`.

## Graceful degradation

| Scenario | Gateway behavior |
|---|---|
| Account-service down on POST /events | 503 + body `{error:"ACCOUNT_SERVICE_UNAVAILABLE"}`. Event is NOT persisted (transactional: persist + apply together; if apply fails, rollback persist). |
| Account-service down on GET /events | 200 — works locally. |
| Account-service down on balance query | Not exposed via gateway — but if it were, would return 503. |
| Circuit breaker open | Same as "down" for POST /events. |

Note: chosen strategy is **persist-then-call** within a single transaction.
If account-service fails, the local event row is rolled back so a retry from
the client (with the same eventId) re-attempts cleanly. Trade-off: an event
received during outage is rejected rather than queued. The bonus
"async fallback queue" is explicitly out of scope unless requested.

## Testing

| Test class | Type | Covers |
|---|---|---|
| `gateway/.../EventServiceTest` | unit | idempotency, out-of-order persistence, validation |
| `gateway/.../GatewayControllerWebMvcTest` | `@WebMvcTest` slice | HTTP status codes, duplicate-event behavior, validation errors |
| `account/.../AccountServiceTest` | unit | balance computation, idempotent transaction application, account auto-create |
| `ResiliencyIntegrationTest` | `@SpringBootTest` with stub account-service that throws | circuit opens after threshold, POST returns 503, GET /events still works |
| `EndToEndIntegrationTest` | boots both apps on RANDOM_PORT | full POST→balance flow, validation, idempotency |
| `TracePropagationTest` | boots both apps | asserts same traceId in gateway and account-service logs |

All runnable via `mvn test` from project root.

## Docker

`docker-compose.yml` builds both modules with `spring-boot:build-image`
(Cloud Native Buildpacks produce OCI images; no manual Dockerfile needed).
Explicit `Dockerfile` files also present for transparency. Profile
`docker` points services at the compose network hostnames
(`account-service:8081`).

## Out of scope (bonus items, NOT built unless requested)

- Jaeger / Zipkin / OTel Collector
- Rate limiting
- Pact contract tests
- Async fallback queue when account-service is down
- Prometheus + Grafana dashboard JSON

These are explicitly mentioned as bonus in the handout. Default: skipped.

## Acceptance

- `mvn test` → all green.
- `docker compose up` → both services start.
- `curl POST /events` (new eventId) → 201, balance updated.
- `curl POST /events` (same eventId) → 200, balance unchanged.
- `curl POST /events` (out-of-order) → 201, balance still correct.
- `curl POST /events` (account-service stopped) → 503.
- `curl GET /events/{id}` (account-service stopped) → 200.
- TraceId in gateway log = traceId in account-service log for same request.
