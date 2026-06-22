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
