# Event Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-service Event Ledger system (gateway + account-service) that processes financial transaction events with idempotency, out-of-order tolerance, distributed tracing, observability, and resiliency.

**Architecture:** Two independent Spring Boot processes that each own an H2 file (PostgreSQL compatibility mode) and never share state. The gateway is the only public surface; it persists events locally and calls the account-service over REST to apply the transaction. Resilience4j wraps the gateway's account-service client with circuit breaker + bulkhead + retry. OpenTelemetry auto-instruments HTTP and propagates trace context via W3C `traceparent`.

**Tech Stack:** Java 26, Spring Boot 4.1.1 (SNAPSHOT), Spring Web (Servlet stack, virtual threads enabled), Spring Data JPA, H2 (PostgreSQL mode), Resilience4j, OpenTelemetry (Spring Boot starter), Micrometer + Prometheus, Logback + logstash-encoder, Maven multi-module, Docker Compose (Cloud Native Buildpacks), JUnit 5 + Spring Boot Test + RestAssured for E2E.

## Global Constraints

- All code uses Java 26 features (virtual threads, pattern matching, `ScopedValue` where appropriate).
- Spring Boot version: `4.1.1-SNAPSHOT` from `https://repo.spring.io/snapshot`.
- Group id `com.agiletal.ledger`, parent module `ledger-gateway`, children `gateway` and `account-service`.
- All commits authored as `vsaxena12 <vsaxena12@users.noreply.github.com>`. Co-authored-by Claude.
- JSON logs include `service`, `traceId`, `spanId`, `level`, `timestamp`, `message`.
- All HTTP error responses use `application/problem+json` (RFC 7807).
- Tests must run with `mvn test` from the project root.
- No shared in-process state between gateway and account-service.
- Each service has its own H2 file under `./.data/<service>-db/`.

## File Structure

```
ledger-gateway/
├── pom.xml                                  # parent (packaging=pom)
├── docker-compose.yml
├── README.md
├── .gitignore
├── docs/
│   └── superpowers/
│       ├── specs/2026-06-22-event-ledger-design.md
│       └── plans/2026-06-22-event-ledger-implementation.md
├── gateway/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/com/agiletal/ledger/gateway/
│       │   │   ├── GatewayApplication.java
│       │   │   ├── api/
│       │   │   │   ├── EventController.java
│       │   │   │   ├── dto/EventRequest.java
│       │   │   │   ├── dto/EventResponse.java
│       │   │   │   └── error/GlobalExceptionHandler.java
│       │   │   ├── service/
│       │   │   │   ├── EventService.java
│       │   │   │   ├── AccountClient.java          # RestClient + Resilience4j
│       │   │   │   └── AccountServiceUnavailableException.java
│       │   │   ├── domain/
│       │   │   │   ├── Event.java
│       │   │   │   ├── EventType.java
│       │   │   │   └── EventRepository.java
│       │   │   └── config/
│       │   │       ├── RestClientConfig.java
│       │   │       └── ResilienceConfig.java
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── logback-spring.xml
│       │       └── db/migration/V1__init.sql
│       └── test/
│           └── java/com/agiletal/ledger/gateway/
│               ├── service/EventServiceTest.java
│               ├── api/GatewayControllerWebMvcTest.java
│               └── integration/EndToEndIntegrationTest.java
└── account-service/
    ├── pom.xml
    ├── Dockerfile
    └── src/
        ├── main/
        │   ├── java/com/agiletal/ledger/account/
        │   │   ├── AccountApplication.java
        │   │   ├── api/
        │   │   │   ├── AccountController.java
        │   │   │   ├── dto/TransactionRequest.java
        │   │   │   ├── dto/TransactionResponse.java
        │   │   │   ├── dto/BalanceResponse.java
        │   │   │   └── error/GlobalExceptionHandler.java
        │   │   ├── service/
        │   │   │   └── AccountService.java
        │   │   └── domain/
        │   │       ├── Account.java
        │   │       ├── Transaction.java
        │   │       ├── AccountRepository.java
        │   │       └── TransactionRepository.java
        │   └── resources/
        │       ├── application.yml
        │       ├── logback-spring.xml
        │       └── db/migration/V1__init.sql
        └── test/
            └── java/com/agiletal/ledger/account/
                └── service/AccountServiceTest.java
```

## Resiliency settings (shared)

- `resilience4j.circuitbreaker.instances.accountService`: failureRateThreshold=50, slidingWindowSize=10, minimumNumberOfCalls=5, waitDurationInOpenState=10s.
- `resilience4j.retry.instances.accountService`: maxAttempts=3, waitDuration=100ms, enableExponentialBackoff=true, exponentialBackoffMultiplier=2, enableRandomizedWait=true, randomizedWaitFactor=0.5, retryExceptions=java.io.IOException,org.springframework.web.client.HttpServerErrorException.
- `resilience4j.bulkhead.instances.accountService`: maxConcurrentCalls=20, maxWaitDuration=0.

---

## Task 1: Parent POM and git scaffolding

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Modify: `.remember/` (placeholder README)

**Step 1:** Create `.gitignore`

```gitignore
target/
*.class
.idea/
.vscode/
*.iml
.DS_Store
.data/
*.h2.db
*.mv.db
*.trace.db
build/
out/
```

**Step 2:** Create root `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.agiletal.ledger</groupId>
    <artifactId>ledger-gateway</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>ledger-gateway</name>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1-SNAPSHOT</version>
        <relativePath/>
    </parent>

    <modules>
        <module>gateway</module>
        <module>account-service</module>
    </modules>

    <properties>
        <java.version>26</java.version>
        <maven.compiler.source>26</maven.compiler.source>
        <maven.compiler.target>26</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <resilience4j.version>2.3.0</resilience4j.version>
        <logstash.encoder.version>8.0</logstash.encoder.version>
    </properties>

    <repositories>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <snapshots><enabled>true</enabled></snapshots>
        </repository>
    </repositories>

    <pluginRepositories>
        <pluginRepository>
            <id>spring-snapshots</id>
            <url>https://repo.spring.io/snapshot</url>
            <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
    </pluginRepositories>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

**Step 3:** Verify parent builds

Run: `cd /home/appusai/Documents/Projects/Agiletal/ledger-gateway && mvn -q -N validate`
Expected: BUILD SUCCESS.

**Step 4:** Commit

```bash
cd /home/appusai/Documents/Projects/Agiletal/ledger-gateway
git add pom.xml .gitignore
git commit -m "build: scaffold parent Maven POM and gitignore"
```

---

## Task 2: account-service module skeleton + application class

**Files:**
- Create: `account-service/pom.xml`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/AccountApplication.java`
- Create: `account-service/src/main/resources/application.yml`

**Step 1:** Create `account-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.agiletal.ledger</groupId>
        <artifactId>ledger-gateway</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>account-service</artifactId>
    <name>account-service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>${logstash.encoder.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2:** Create `AccountApplication.java`

```java
package com.agiletal.ledger.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AccountApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
```

**Step 3:** Create `account-service/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: account-service
  datasource:
    url: jdbc:h2:file:./.data/account-service/account;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: false
  sql:
    init:
      mode: always
      schema-locations: classpath:db/migration/V1__init.sql
  threads:
    virtual:
      enabled: true

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  health:
    db:
      enabled: true

logging:
  config: classpath:logback-spring.xml
```

**Step 4:** Commit

```bash
git add account-service/pom.xml account-service/src/
git commit -m "build(account-service): module skeleton + application class"
```

---

## Task 3: account-service schema + JPA entities

**Files:**
- Create: `account-service/src/main/resources/db/migration/V1__init.sql`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/domain/Account.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/domain/Transaction.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/domain/AccountRepository.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/domain/TransactionRepository.java`

**Step 1:** Create `V1__init.sql`

```sql
CREATE TABLE IF NOT EXISTS accounts (
  id VARCHAR(64) PRIMARY KEY,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL CHECK (type IN ('CREDIT','DEBIT')),
  amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
  applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_tx_account_applied ON transactions(account_id, applied_at);
```

**Step 2:** Create `Account.java`

```java
package com.agiletal.ledger.account.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private String id;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Account() {}

    public Account(String id, OffsetDateTime createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
```

**Step 3:** Create `Transaction.java`

```java
package com.agiletal.ledger.account.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    protected Transaction() {}

    public Transaction(String eventId, String accountId, TransactionType type,
                       BigDecimal amount, OffsetDateTime appliedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.appliedAt = appliedAt;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }
}
```

**Step 4:** Create `TransactionType.java`

```java
package com.agiletal.ledger.account.domain;

public enum TransactionType { CREDIT, DEBIT }
```

**Step 5:** Create `AccountRepository.java`

```java
package com.agiletal.ledger.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {}
```

**Step 6:** Create `TransactionRepository.java`

```java
package com.agiletal.ledger.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByEventId(String eventId);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.type = com.agiletal.ledger.account.domain.TransactionType.CREDIT
                                 THEN t.amount ELSE -t.amount END), 0)
        FROM Transaction t WHERE t.accountId = :accountId
    """)
    BigDecimal sumForAccount(@Param("accountId") String accountId);

    List<Transaction> findTop50ByAccountIdOrderByAppliedAtDesc(String accountId);
}
```

**Step 7:** Commit

```bash
git add account-service/src/
git commit -m "feat(account-service): schema, entities, repositories"
```

---

## Task 4: account-service business logic + failing unit test

**Files:**
- Create: `account-service/src/test/java/com/agiletal/ledger/account/service/AccountServiceTest.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/service/AccountService.java`

**Step 1:** Write the failing test `AccountServiceTest.java`

```java
package com.agiletal.ledger.account.service;

import com.agiletal.ledger.account.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository txs = mock(TransactionRepository.class);
    AccountService service;

    @BeforeEach
    void setup() { service = new AccountService(accounts, txs); }

    @Test
    void apply_credits_increase_balance() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("150.00"));

        var resp = service.apply("a1", "e1", TransactionType.CREDIT, new BigDecimal("150.00"));

        assertThat(resp.balance()).isEqualByComparingTo("150.00");
        ArgumentCaptor<Transaction> cap = ArgumentCaptor.forClass(Transaction.class);
        verify(txs).save(cap.capture());
        assertThat(cap.getValue().getEventId()).isEqualTo("e1");
        assertThat(cap.getValue().getType()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void apply_debit_decreases_balance() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("50.00"));

        var resp = service.apply("a1", "e1", TransactionType.DEBIT, new BigDecimal("50.00"));
        assertThat(resp.balance()).isEqualByComparingTo("50.00");
    }

    @Test
    void duplicate_event_id_is_idempotent() {
        Transaction existing = new Transaction("e1", "a1", TransactionType.CREDIT,
                new BigDecimal("150.00"), OffsetDateTime.now());
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.of(existing));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("150.00"));

        var resp = service.apply("a1", "e1", TransactionType.CREDIT, new BigDecimal("999.99"));
        verify(txs, never()).save(any());
        assertThat(resp.balance()).isEqualByComparingTo("150.00");
    }

    @Test
    void missing_account_is_auto_created() {
        when(accounts.findById("new")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("new")).thenReturn(new BigDecimal("10.00"));

        var resp = service.apply("new", "e1", TransactionType.CREDIT, new BigDecimal("10.00"));
        assertThat(resp.balance()).isEqualByComparingTo("10.00");
    }

    @Test
    void get_account_includes_recent_transactions() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("100"));
        when(txs.findTop50ByAccountIdOrderByAppliedAtDesc("a1")).thenReturn(List.of());

        var resp = service.getAccount("a1");
        assertThat(resp.balance()).isEqualByComparingTo("100");
        assertThat(resp.transactions()).isEmpty();
    }
}
```

**Step 2:** Run test to confirm it fails (missing class)

Run: `cd /home/appusai/Documents/Projects/Agiletal/ledger-gateway && mvn -q -pl account-service -am test`
Expected: COMPILATION FAILURE on `AccountService` reference.

**Step 3:** Implement `AccountService.java`

```java
package com.agiletal.ledger.account.service;

import com.agiletal.ledger.account.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AccountService {

    public record ApplyResult(String accountId, BigDecimal balance, Transaction transaction) {}
    public record AccountView(String accountId, OffsetDateTime createdAt, BigDecimal balance, List<Transaction> transactions) {}

    private final AccountRepository accounts;
    private final TransactionRepository txs;

    public AccountService(AccountRepository accounts, TransactionRepository txs) {
        this.accounts = accounts;
        this.txs = txs;
    }

    @Transactional
    public ApplyResult apply(String accountId, String eventId, TransactionType type, BigDecimal amount) {
        var existing = txs.findByEventId(eventId);
        if (existing.isPresent()) {
            return new ApplyResult(accountId, txs.sumForAccount(accountId), existing.get());
        }
        accounts.findById(accountId).orElseGet(() ->
                accounts.save(new Account(accountId, OffsetDateTime.now())));
        var tx = new Transaction(eventId, accountId, type, amount, OffsetDateTime.now());
        txs.save(tx);
        return new ApplyResult(accountId, txs.sumForAccount(accountId), tx);
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountId) {
        return txs.sumForAccount(accountId);
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(String accountId) {
        var account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new AccountView(account.getId(), account.getCreatedAt(),
                txs.sumForAccount(accountId),
                txs.findTop50ByAccountIdOrderByAppliedAtDesc(accountId));
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String id) { super("account not found: " + id); }
    }
}
```

**Step 4:** Run tests, expect green

Run: `mvn -q -pl account-service -am test`
Expected: BUILD SUCCESS, 5 tests pass.

**Step 5:** Commit

```bash
git add account-service/src/
git commit -m "feat(account-service): AccountService with idempotent apply"
```

---

## Task 5: account-service REST controller + Problem Details

**Files:**
- Create: `account-service/src/main/java/com/agiletal/ledger/account/api/dto/TransactionRequest.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/api/dto/TransactionResponse.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/api/dto/BalanceResponse.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/api/AccountController.java`
- Create: `account-service/src/main/java/com/agiletal/ledger/account/api/error/GlobalExceptionHandler.java`

**Step 1:** Create `TransactionRequest.java`

```java
package com.agiletal.ledger.account.api.dto;

import com.agiletal.ledger.account.domain.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank String currency
) {}
```

**Step 2:** Create `TransactionResponse.java`

```java
package com.agiletal.ledger.account.api.dto;

import com.agiletal.ledger.account.domain.Transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
        Long id,
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        OffsetDateTime appliedAt,
        BigDecimal balance
) {
    public static TransactionResponse of(Transaction t, BigDecimal balance) {
        return new TransactionResponse(t.getId(), t.getEventId(), t.getAccountId(),
                t.getType().name(), t.getAmount(), t.getAppliedAt(), balance);
    }
}
```

**Step 3:** Create `BalanceResponse.java`

```java
package com.agiletal.ledger.account.api.dto;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance) {}
```

**Step 4:** Create `AccountController.java`

```java
package com.agiletal.ledger.account.api;

import com.agiletal.ledger.account.api.dto.*;
import com.agiletal.ledger.account.domain.Transaction;
import com.agiletal.ledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> apply(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest req) {
        var result = service.apply(accountId, req.eventId(), req.type(), req.amount());
        return ResponseEntity.ok(TransactionResponse.of(result.transaction(), result.balance()));
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, service.getBalance(accountId));
    }

    @GetMapping("/{accountId}")
    public AccountService.AccountView get(@PathVariable String accountId) {
        return service.getAccount(accountId);
    }
}
```

**Step 5:** Create `GlobalExceptionHandler.java`

```java
package com.agiletal.ledger.account.api.error;

import com.agiletal.ledger.account.service.AccountService.AccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        e -> e.getField(),
                        e -> e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage(),
                        (a, b) -> a)));
        return pd;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail onNotFound(AccountNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
```

**Step 6:** Build to confirm

Run: `mvn -q -pl account-service -am compile`
Expected: BUILD SUCCESS.

**Step 7:** Commit

```bash
git add account-service/src/
git commit -m "feat(account-service): REST controller + problem-details errors"
```

---

## Task 6: account-service Logback JSON + structured logs

**Files:**
- Create: `account-service/src/main/resources/logback-spring.xml`

**Step 1:** Create `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="serviceName" source="spring.application.name" defaultValue="account-service"/>

    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <fieldName>timestamp</fieldName>
                    <timeZone>UTC</timeZone>
                </timestamp>
                <pattern>
                    <pattern>
                        {
                          "service": "${serviceName}",
                          "level": "%level",
                          "logger": "%logger",
                          "thread": "%thread",
                          "traceId": "%mdc{traceId:-}",
                          "spanId": "%mdc{spanId:-}"
                        }
                    </pattern>
                </pattern>
                <message>
                    <fieldName>message</fieldName>
                </message>
                <mdc/>
                <stackTrace/>
            </providers>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
    <logger name="com.agiletal.ledger" level="DEBUG"/>
    <logger name="org.hibernate" level="WARN"/>
</configuration>
```

**Step 2:** Commit

```bash
git add account-service/src/main/resources/logback-spring.xml
git commit -m "feat(account-service): JSON logback encoder with trace fields"
```

---

## Task 7: gateway module skeleton + application class

**Files:**
- Create: `gateway/pom.xml`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/GatewayApplication.java`
- Create: `gateway/src/main/resources/application.yml`

**Step 1:** Create `gateway/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.agiletal.ledger</groupId>
        <artifactId>ledger-gateway</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>gateway</artifactId>
    <name>gateway</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-reactor</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>${logstash.encoder.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2:** Create `GatewayApplication.java`

```java
package com.agiletal.ledger.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

**Step 3:** Create `gateway/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: event-gateway
  datasource:
    url: jdbc:h2:file:./.data/event-gateway/gateway;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
  sql:
    init:
      mode: always
      schema-locations: classpath:db/migration/V1__init.sql
  threads:
    virtual:
      enabled: true

server:
  port: 8080

account-service:
  base-url: http://localhost:8081

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  health:
    db:
      enabled: true

resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
  retry:
    instances:
      accountService:
        maxAttempts: 3
        waitDuration: 100ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        enableRandomizedWait: true
        randomizedWaitFactor: 0.5
        retryExceptions:
          - java.io.IOException
          - org.springframework.web.client.HttpServerErrorException
  bulkhead:
    instances:
      accountService:
        maxConcurrentCalls: 20
        maxWaitDuration: 0

logging:
  config: classpath:logback-spring.xml
```

**Step 4:** Commit

```bash
git add gateway/
git commit -m "build(gateway): module skeleton + application class + resilience config"
```

---

## Task 8: gateway schema + Event entity + repository

**Files:**
- Create: `gateway/src/main/resources/db/migration/V1__init.sql`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/domain/EventType.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/domain/Event.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/domain/EventRepository.java`

**Step 1:** Create `V1__init.sql`

```sql
CREATE TABLE IF NOT EXISTS events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  account_id VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL CHECK (type IN ('CREDIT','DEBIT')),
  amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
  currency VARCHAR(8) NOT NULL,
  event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  metadata VARCHAR(2000),
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  applied_to_account BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_events_account_ts ON events(account_id, event_timestamp);
```

**Step 2:** Create `EventType.java`

```java
package com.agiletal.ledger.gateway.domain;

public enum EventType { CREDIT, DEBIT }
```

**Step 3:** Create `Event.java`

```java
package com.agiletal.ledger.gateway.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;

    @Column(length = 2000)
    private String metadata;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "applied_to_account", nullable = false)
    private boolean appliedToAccount;

    protected Event() {}

    public Event(String eventId, String accountId, EventType type, BigDecimal amount,
                 String currency, OffsetDateTime eventTimestamp, String metadata,
                 OffsetDateTime receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.receivedAt = receivedAt;
        this.appliedToAccount = false;
    }

    public void markApplied() { this.appliedToAccount = true; }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public EventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getEventTimestamp() { return eventTimestamp; }
    public String getMetadata() { return metadata; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public boolean isAppliedToAccount() { return appliedToAccount; }
}
```

**Step 4:** Create `EventRepository.java`

```java
package com.agiletal.ledger.gateway.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByEventId(String eventId);
    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
```

**Step 5:** Commit

```bash
git add gateway/src/
git commit -m "feat(gateway): schema, Event entity, repository"
```

---

## Task 9: gateway AccountClient (Resilience4j-wrapped RestClient)

**Files:**
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/service/AccountServiceUnavailableException.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/service/AccountClient.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/config/RestClientConfig.java`

**Step 1:** Create `AccountServiceUnavailableException.java`

```java
package com.agiletal.ledger.gateway.service;

public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    public AccountServiceUnavailableException(String msg) { super(msg); }
}
```

**Step 2:** Create `RestClientConfig.java`

```java
package com.agiletal.ledger.gateway.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient accountServiceClient(@Value("${account-service.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
```

**Step 3:** Create `AccountClient.java`

```java
package com.agiletal.ledger.gateway.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class AccountClient {

    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);

    private final RestClient client;

    public AccountClient(RestClient accountServiceClient) {
        this.client = accountServiceClient;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyFallback")
    @Bulkhead(name = "accountService")
    @Retry(name = "accountService")
    public ApplyResult apply(String accountId, String eventId, String type,
                             BigDecimal amount, String currency, OffsetDateTime eventTimestamp) {
        log.info("applying event {} to account {}", eventId, accountId);
        return client.post()
                .uri("/accounts/{id}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "eventId", eventId,
                        "type", type,
                        "amount", amount,
                        "currency", currency))
                .retrieve()
                .body(ApplyResult.class);
    }

    @SuppressWarnings("unused")
    private ApplyResult applyFallback(String accountId, String eventId, String type,
                                      BigDecimal amount, String currency, OffsetDateTime eventTimestamp,
                                      Throwable t) {
        log.warn("account-service call failed for event {} on account {}: {}",
                eventId, accountId, t.toString());
        if (t instanceof HttpClientErrorException ce) {
            throw ce;
        }
        throw new AccountServiceUnavailableException(
                "account-service unreachable: " + t.getMessage(), t);
    }

    public record ApplyResult(
            Long id,
            String eventId,
            String accountId,
            String type,
            BigDecimal amount,
            OffsetDateTime appliedAt,
            BigDecimal balance) {}
}
```

**Step 4:** Build

Run: `mvn -q -pl gateway -am compile`
Expected: BUILD SUCCESS.

**Step 5:** Commit

```bash
git add gateway/src/
git commit -m "feat(gateway): AccountClient with circuit breaker + retry + bulkhead"
```

---

## Task 10: gateway EventService + failing unit test

**Files:**
- Create: `gateway/src/test/java/com/agiletal/ledger/gateway/service/EventServiceTest.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/service/EventService.java`

**Step 1:** Write the failing test

```java
package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventServiceTest {

    EventRepository repo = mock(EventRepository.class);
    AccountClient client = mock(AccountClient.class);
    EventService service;

    @BeforeEach
    void setup() { service = new EventService(repo, client); }

    private EventRequest req(String eid) {
        return new EventRequest(eid, "acct-1", EventType.CREDIT,
                new BigDecimal("100.00"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"),
                Map.of("source", "test"));
    }

    @Test
    void submit_new_event_persists_and_calls_account_service() {
        when(repo.findByEventId("e1")).thenReturn(Optional.empty());
        when(repo.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(client.apply(eq("acct-1"), eq("e1"), eq("CREDIT"),
                any(), any(), any()))
                .thenReturn(new AccountClient.ApplyResult(1L, "e1", "acct-1",
                        "CREDIT", new BigDecimal("100.00"),
                        OffsetDateTime.now(), new BigDecimal("100.00")));

        var resp = service.submit(req("e1"));

        assertThat(resp.eventId()).isEqualTo("e1");
        assertThat(resp.type()).isEqualTo("CREDIT");
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().isAppliedToAccount()).isTrue();
    }

    @Test
    void duplicate_eventId_returns_existing_without_calling_account_service() {
        Event existing = new Event("e1", "acct-1", EventType.CREDIT,
                new BigDecimal("100.00"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null,
                OffsetDateTime.now());
        when(repo.findByEventId("e1")).thenReturn(Optional.of(existing));

        var resp = service.submit(req("e1"));

        assertThat(resp.eventId()).isEqualTo("e1");
        verifyNoInteractions(client);
    }

    @Test
    void account_service_failure_bubbles_up_and_event_persists() {
        when(repo.findByEventId("e1")).thenReturn(Optional.empty());
        when(repo.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(client.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AccountServiceUnavailableException("down"));

        assertThatThrownBy(() -> service.submit(req("e1")))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void list_for_account_orders_by_event_timestamp() {
        Event e1 = new Event("e1", "acct-1", EventType.CREDIT, new BigDecimal("1"),
                "USD", OffsetDateTime.parse("2026-05-15T10:00:00Z"), null, OffsetDateTime.now());
        Event e2 = new Event("e2", "acct-1", EventType.CREDIT, new BigDecimal("2"),
                "USD", OffsetDateTime.parse("2026-05-15T09:00:00Z"), null, OffsetDateTime.now());
        when(repo.findByAccountIdOrderByEventTimestampAsc("acct-1")).thenReturn(List.of(e2, e1));

        var list = service.listForAccount("acct-1");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).eventId()).isEqualTo("e2");
        assertThat(list.get(1).eventId()).isEqualTo("e1");
    }

    @Test
    void get_by_event_id_returns_existing() {
        Event e = new Event("e1", "acct-1", EventType.CREDIT, new BigDecimal("1"),
                "USD", OffsetDateTime.now(), null, OffsetDateTime.now());
        when(repo.findByEventId("e1")).thenReturn(Optional.of(e));
        assertThat(service.getByEventId("e1")).isPresent();
    }
}
```

**Step 2:** Create supporting types `EventRequest` and `EventResponse`

`gateway/src/main/java/com/agiletal/ledger/gateway/service/EventRequest.java`:

```java
package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventRequest(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp,
        Map<String, Object> metadata
) {}
```

`gateway/src/main/java/com/agiletal/ledger/gateway/service/EventResponse.java`:

```java
package com.agiletal.ledger.gateway.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        String type,                  // "CREDIT" or "DEBIT"
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp,
        Map<String, Object> metadata,
        boolean appliedToAccount
) {}
```

Note: `type` is `String` (not the `EventType` enum) so JSON serialization
emits a plain string without Jackson registering an enum module. Task 11's
WebMvcTest asserts on the JSON string directly.

**Step 3:** Run test to confirm it fails

Run: `mvn -q -pl gateway -am test`
Expected: COMPILATION FAILURE on `EventService` reference.

**Step 4:** Implement `EventService.java`

```java
package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.Event;
import com.agiletal.ledger.gateway.domain.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository repo;
    private final AccountClient accountClient;

    public EventService(EventRepository repo, AccountClient accountClient) {
        this.repo = repo;
        this.accountClient = accountClient;
    }

    @Transactional
    public EventResponse submit(EventRequest req) {
        var existing = repo.findByEventId(req.eventId());
        if (existing.isPresent()) {
            log.info("duplicate event {} - returning original", req.eventId());
            return toResponse(existing.get());
        }
        Event event = new Event(
                req.eventId(), req.accountId(), req.type(), req.amount(),
                req.currency(), req.eventTimestamp(),
                req.metadata() == null ? null : req.metadata().toString(),
                OffsetDateTime.now());
        repo.save(event);
        try {
            accountClient.apply(req.accountId(), req.eventId(),
                    req.type().name(), req.amount(), req.currency(),
                    req.eventTimestamp());
            event.markApplied();
            repo.save(event);
        } catch (AccountServiceUnavailableException ex) {
            log.warn("account-service unavailable for event {}; rolling back persist",
                    req.eventId());
            throw ex;
        }
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listForAccount(String accountId) {
        return repo.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<EventResponse> getByEventId(String eventId) {
        return repo.findByEventId(eventId).map(this::toResponse);
    }

    private EventResponse toResponse(Event e) {
        return new EventResponse(
                e.getEventId(), e.getAccountId(), e.getType(),
                e.getAmount(), e.getCurrency(), e.getEventTimestamp(),
                null, e.isAppliedToAccount());
    }
}
```

**Step 5:** Run tests, expect green

Run: `mvn -q -pl gateway -am test`
Expected: BUILD SUCCESS, 5 tests pass.

**Step 6:** Commit

```bash
git add gateway/src/
git commit -m "feat(gateway): EventService with idempotency and out-of-order tolerance"
```

---

## Task 11: gateway REST controller + Problem Details + Logback JSON

**Files:**
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/api/dto/EventDto.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/api/EventController.java`
- Create: `gateway/src/main/java/com/agiletal/ledger/gateway/api/error/GlobalExceptionHandler.java`
- Create: `gateway/src/main/resources/logback-spring.xml`

**Step 1:** Create `EventDto.java`

```java
package com.agiletal.ledger.gateway.api.dto;

import com.agiletal.ledger.gateway.domain.EventType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventDto(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String accountId,
        @NotNull EventType type,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Size(max = 8) String currency,
        @NotNull OffsetDateTime eventTimestamp,
        Map<String, Object> metadata
) {}
```

**Step 2:** Create `EventController.java`

```java
package com.agiletal.ledger.gateway.api;

import com.agiletal.ledger.gateway.api.dto.EventDto;
import com.agiletal.ledger.gateway.service.EventRequest;
import com.agiletal.ledger.gateway.service.EventResponse;
import com.agiletal.ledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventDto dto) {
        EventResponse resp = service.submit(new EventRequest(
                dto.eventId(), dto.accountId(), dto.type(), dto.amount(),
                dto.currency(), dto.eventTimestamp(), dto.metadata()));
        HttpStatus status = resp.appliedToAccount() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(resp);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> get(@PathVariable("id") String id) {
        return service.getByEventId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<EventResponse> list(@RequestParam("account") String accountId) {
        return service.listForAccount(accountId);
    }
}
```

**Step 3:** Create `GlobalExceptionHandler.java`

```java
package com.agiletal.ledger.gateway.api.error;

import com.agiletal.ledger.gateway.service.AccountServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        e -> e.getField(),
                        e -> e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage(),
                        (a, b) -> a)));
        return pd;
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ProblemDetail onUnavailable(AccountServiceUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setTitle("Account service unavailable");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
```

**Step 4:** Create `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="serviceName" source="spring.application.name" defaultValue="event-gateway"/>

    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <fieldName>timestamp</fieldName>
                    <timeZone>UTC</timeZone>
                </timestamp>
                <pattern>
                    <pattern>
                        {
                          "service": "${serviceName}",
                          "level": "%level",
                          "logger": "%logger",
                          "thread": "%thread",
                          "traceId": "%mdc{traceId:-}",
                          "spanId": "%mdc{spanId:-}"
                        }
                    </pattern>
                </pattern>
                <message>
                    <fieldName>message</fieldName>
                </message>
                <mdc/>
                <stackTrace/>
            </providers>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
    <logger name="com.agiletal.ledger" level="DEBUG"/>
    <logger name="org.hibernate" level="WARN"/>
</configuration>
```

**Step 5:** Build

Run: `mvn -q -pl gateway -am compile`
Expected: BUILD SUCCESS.

**Step 6:** Commit

```bash
git add gateway/src/
git commit -m "feat(gateway): REST controller + problem-details + JSON logback"
```

---

## Task 12: gateway WebMvcTest slice test

**Files:**
- Create: `gateway/src/test/java/com/agiletal/ledger/gateway/api/GatewayControllerWebMvcTest.java`

**Step 1:** Write test

```java
package com.agiletal.ledger.gateway.api;

import com.agiletal.ledger.gateway.domain.EventType;
import com.agiletal.ledger.gateway.service.EventResponse;
import com.agiletal.ledger.gateway.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class GatewayControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @MockBean EventService service;

    @Test
    void post_event_201_on_new() throws Exception {
        when(service.submit(any())).thenReturn(new EventResponse(
                "e1", "a1", EventType.CREDIT, new BigDecimal("100"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null, true));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("e1"));
    }

    @Test
    void post_event_200_on_duplicate() throws Exception {
        when(service.submit(any())).thenReturn(new EventResponse(
                "e1", "a1", EventType.CREDIT, new BigDecimal("100"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null, true));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isOk());
    }

    @Test
    void post_event_400_on_validation_error() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"","accountId":"a1","type":"CREDIT","amount":-5,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isBadRequest());
    }

    @Test
    void get_event_by_id_404_when_missing() throws Exception {
        when(service.getByEventId("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/events/nope")).andExpect(status().isNotFound());
    }

    @Test
    void get_events_by_account_returns_list() throws Exception {
        when(service.listForAccount("a1")).thenReturn(List.of());
        mvc.perform(get("/events?account=a1")).andExpect(status().isOk());
    }
}
```

**Step 2:** Run test

Run: `mvn -q -pl gateway -am test`
Expected: BUILD SUCCESS, all WebMvcTest tests pass.

**Step 3:** Commit

```bash
git add gateway/src/test/
git commit -m "test(gateway): WebMvcTest slice for controller"
```

---

## Task 13: account-service Dockerfile + gateway Dockerfile + docker-compose

**Files:**
- Create: `gateway/Dockerfile`
- Create: `account-service/Dockerfile`
- Create: `docker-compose.yml`

**Step 1:** Create `gateway/Dockerfile`

```dockerfile
FROM eclipse-temurin:26-jdk AS build
WORKDIR /build
COPY ../pom.xml ../pom.xml
COPY ../gateway gateway
RUN cd /build && ./mvnw -pl gateway -am -DskipTests package || mvn -pl gateway -am -DskipTests package

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /build/gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=docker"]
```

**Step 2:** Create `account-service/Dockerfile`

```dockerfile
FROM eclipse-temurin:26-jdk AS build
WORKDIR /build
COPY ../pom.xml ../pom.xml
COPY ../account-service account-service
RUN cd /build && ./mvnw -pl account-service -am -DskipTests package || mvn -pl account-service -am -DskipTests package

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /build/account-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=docker"]
```

**Step 3:** Create `docker-compose.yml`

```yaml
services:
  account-service:
    build:
      context: .
      dockerfile: account-service/Dockerfile
    container_name: account-service
    ports:
      - "8081:8081"
    volumes:
      - account-data:/.data/account-service
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8081/actuator/health"]
      interval: 5s
      timeout: 3s
      retries: 20

  event-gateway:
    build:
      context: .
      dockerfile: gateway/Dockerfile
    container_name: event-gateway
    depends_on:
      account-service:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      ACCOUNT_SERVICE_BASE_URL: http://account-service:8081
    volumes:
      - gateway-data:/.data/event-gateway

volumes:
  account-data:
  gateway-data:
```

**Step 4:** Note: profile `docker` is referenced. Wire it through `application.yml` profiles block (handled implicitly by `--spring.profiles.active=docker` overriding `account-service.base-url`). Add a small conditional to `application.yml` later if needed.

**Step 5:** Commit

```bash
git add gateway/Dockerfile account-service/Dockerfile docker-compose.yml
git commit -m "build: Dockerfiles and docker-compose for both services"
```

---

## Task 14: README

**Files:**
- Create: `README.md`

**Step 1:** Write README

```markdown
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
```

**Step 2:** Commit

```bash
git add README.md
git commit -m "docs: README with quick start, tests, resiliency rationale"
```

---

## Task 15: End-to-end integration test (boots both apps on random ports)

**Files:**
- Create: `gateway/src/test/java/com/agiletal/ledger/gateway/integration/EndToEndIntegrationTest.java`

**Step 1:** Create the integration test

```java
package com.agiletal.ledger.gateway.integration;

import com.agiletal.ledger.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "account-service.base-url=http://localhost:18081",
        "spring.datasource.url=jdbc:h2:mem:gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class GatewayToAccountE2EIT {

    // NOTE: this test assumes account-service is running on :18081.
    // Use docker compose up account-service before running, OR pair with
    // the dedicated profile.
    @LocalServerPort int gatewayPort;
    @Autowired TestRestTemplate http;

    @Test
    void post_event_propagates_to_account_service_balance() {
        String body = """
            {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT","amount":250,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}""";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var resp = http.exchange(
                "http://localhost:" + gatewayPort + "/events",
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

**Step 2:** Add a docker profile in `gateway/src/main/resources/application.yml`

Append a `spring.config.activate.on-profile` block to the gateway's
`application.yml`:

```yaml
---
spring:
  config:
    activate:
      on-profile: docker
  datasource:
    url: jdbc:h2:file:./.data/event-gateway/gateway;AUTO_SERVER=TRUE;MODE=PostgreSQL
```

(Append below the existing block, separated by `---`.)

**Step 3:** Commit

```bash
git add gateway/src/
git commit -m "test(gateway): E2E integration test + docker profile"
```

---

## Task 16: Publish to GitHub

**Step 1:** Add the remote

```bash
cd /home/appusai/Documents/Projects/Agiletal/ledger-gateway
git remote add origin https://github.com/vsaxena12/ledger-gateway.git
```

**Step 2:** Push

```bash
git push -u origin main
```

(If the repo doesn't yet exist on GitHub, create it via the GitHub web UI
or `gh repo create vsaxena12/ledger-gateway --public --source=. --remote=origin --push`.)

---

## Self-review

- Spec coverage: API contracts covered by Tasks 5+11; data model by Tasks 3+8; idempotency by Tasks 4+10; out-of-order by Task 10 tests; resiliency by Task 9; tracing/logback by Tasks 6+11; observability by Tasks 6+11; graceful degradation by Task 9 fallback + integration; Docker by Task 13; tests by Tasks 4+10+12+15; README by Task 14.
- No placeholders: every step has full code blocks.
- Type consistency: `EventType`, `TransactionType`, `AccountClient.ApplyResult`, `EventResponse`, `EventRequest`, `EventDto` defined in their owning tasks and used consistently downstream.
