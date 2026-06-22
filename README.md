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

Custom trace propagation using W3C `traceparent` headers. Trace IDs flow
from the gateway through to the account-service and are logged in JSON
format by both services.

When running with Docker Compose, traces are also sent to Jaeger for
visualization at http://localhost:16686.

## Observability

- JSON logs: `application.log`
- Metrics: `GET /actuator/prometheus`
- Health: `GET /actuator/health`

## Bonus Features

### Tracing Visualization (Jaeger)

Start all services including the OpenTelemetry Collector and Jaeger:

```bash
docker compose up --build
```

Access the Jaeger UI at http://localhost:16686 to visualize traces.

### Rate Limiting

The gateway uses Resilience4j RateLimiter on the account-service client,
limiting to 50 requests per second.

### Async Fallback Queue

When the account-service is unavailable, events are persisted locally with
`appliedToAccount=false`. A background scheduled task retries them every 30
seconds (max 5 retries). This prevents event loss during outages.

### Prometheus Metrics

- `GET /actuator/prometheus` on both services
- Custom metrics: `gateway.events.received`, `gateway.account_service.latency`
- Resilience4j circuit breaker state gauges automatically reported
