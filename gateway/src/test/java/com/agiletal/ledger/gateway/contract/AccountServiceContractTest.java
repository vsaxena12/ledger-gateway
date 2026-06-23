package com.agiletal.ledger.gateway.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.agiletal.ledger.gateway.service.AccountClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "account-service")
class AccountServiceContractTest {

    @Pact(consumer = "event-gateway")
    public RequestResponsePact createTransactionPact(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact-test exists")
                .uponReceiving("a request to apply a CREDIT transaction")
                .path("/accounts/acct-pact-test/transactions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(newJsonBody(body -> {
                    body.stringType("eventId", "pact-evt-1");
                    body.stringType("type", "CREDIT");
                    body.numberType("amount", 100.00);
                    body.stringType("currency", "USD");
                }).build())
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(newJsonBody(body -> {
                    body.numberType("id", 1);
                    body.stringType("eventId", "pact-evt-1");
                    body.stringType("accountId", "acct-pact-test");
                    body.stringType("type", "CREDIT");
                    body.numberType("amount", 100.00);
                    body.numberType("balance", 100.00);
                }).build())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createTransactionPact")
    void shouldCallAccountServiceWithCorrectRequestAndHandleResponse(MockServer mockServer) {
        RestClient restClient = RestClient.builder()
                .baseUrl(mockServer.getUrl())
                .build();
        AccountClient client = new AccountClient(restClient);

        AccountClient.ApplyResult result = client.apply(
                "acct-pact-test", "pact-evt-1", "CREDIT",
                new BigDecimal("100.00"), "USD",
                OffsetDateTime.parse("2026-06-22T12:00:00Z"));

        assertThat(result).isNotNull();
        assertThat(result.eventId()).isEqualTo("pact-evt-1");
        assertThat(result.type()).isEqualTo("CREDIT");
        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Pact(consumer = "event-gateway")
    public RequestResponsePact createDebitTransactionPact(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact-test exists")
                .uponReceiving("a request to apply a DEBIT transaction")
                .path("/accounts/acct-pact-test/transactions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(newJsonBody(body -> {
                    body.stringType("eventId", "pact-evt-2");
                    body.stringType("type", "DEBIT");
                    body.numberType("amount", 50.00);
                    body.stringType("currency", "USD");
                }).build())
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(newJsonBody(body -> {
                    body.numberType("id", 2);
                    body.stringType("eventId", "pact-evt-2");
                    body.stringType("accountId", "acct-pact-test");
                    body.stringType("type", "DEBIT");
                    body.numberType("amount", 50.00);
                    body.numberType("balance", 50.00);
                }).build())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createDebitTransactionPact")
    void shouldHandleDebitTransactionCorrectly(MockServer mockServer) {
        RestClient restClient = RestClient.builder()
                .baseUrl(mockServer.getUrl())
                .build();
        AccountClient client = new AccountClient(restClient);

        AccountClient.ApplyResult result = client.apply(
                "acct-pact-test", "pact-evt-2", "DEBIT",
                new BigDecimal("50.00"), "USD",
                OffsetDateTime.parse("2026-06-22T13:00:00Z"));

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("DEBIT");
    }
}
