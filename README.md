# Event Ledger

Two Spring Boot 4.1.1 / Java 26 microservices that process financial
transaction events with idempotency, out-of-order tolerance, distributed
tracing, observability, and resiliency.

## Architecture

- **event-gateway** (port 8080, public) — receives events, persists them
  locally with idempotency keys, calls account-service.
- **account-service** (port 8081, internal) — applies transactions to
  account state, returns balance.

Each service has its own H2 file (PostgreSQL compatibility mode). No
shared state. Synchronous REST + `traceparent` propagation.

## Quick start

### Run with Maven

```bash
mvn -pl account-service -am spring-boot:run &
mvn -pl gateway -am spring-boot:run
```

### Run with Docker Compose

```bash
docker compose up --build
```

## Smoke test

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
       "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

curl http://localhost:8080/events?account=a1
```

## Tests

```bash
mvn test
```

Covers: unit tests for both services, WebMvcTest slice for the gateway,
end-to-end integration booting both apps.

## Resiliency

The gateway wraps the account-service call with Resilience4j:

- **Circuit breaker** — 50% failure rate over a 10-call sliding window
  opens the breaker for 10 seconds.
- **Retry** — 3 attempts with exponential backoff (100ms → 200ms →
  400ms) plus jitter, only on connect errors and 5xx.
- **Bulkhead** — `SemaphoreBulkhead(maxConcurrentCalls=20)`. Chosen over
  thread-pool bulkhead because Java 26 virtual threads eliminate the
  thread-pool-exhaustion concern.

Decorator order: `Retry(Bulkhead(CircuitBreaker(RestClient)))`.

When the account-service is unreachable or the circuit is open, `POST
/events` returns `503 Service Unavailable`. `GET /events/{id}` and
`GET /events?account=...` continue to work because they only read the
gateway's local data.

## Tracing

OpenTelemetry auto-instrumentation propagates trace context from the
gateway to the account-service via the W3C `traceparent` header. Both
services include `traceId` and `spanId` in their JSON logs.

## Observability

- JSON logs: `application.log`
- Metrics: `GET /actuator/prometheus`
- Health: `GET /actuator/health`
