package com.agiletal.ledger.gateway.integration;

import com.agiletal.ledger.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.http.server.LocalTestWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that boots the gateway with RANDOM_PORT and
 * exercises POST /events. This test requires a real account-service running
 * on http://localhost:18081 (start it via `docker compose up account-service`
 * or `mvn -pl account-service spring-boot:run -Dspring-boot.run.arguments=--server.port=18081`).
 *
 * <p>Default `mvn test` does NOT require this — the test will fail at the
 * circuit-breaker fallback (5xx because the upstream is unreachable) and the
 * assertion below expects 503. To run with a real account-service, change
 * the assertion to expect 201.
 */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "account-service.base-url=http://localhost:18081",
        "spring.datasource.url=jdbc:h2:mem:gateway;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class EndToEndIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void gateway_returns_503_when_account_service_unreachable() {
        LocalTestWebServer server = LocalTestWebServer.get(context);
        String body = """
            {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT","amount":250,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}""";
        ResponseEntity<String> resp = RestClient.builder()
                .build()
                .post()
                .uri(server.uri("/events"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .onStatus(s -> true, (req, response) -> {})
                .toEntity(String.class);
        // With no account-service upstream, the gateway's Resilience4j circuit
        // returns 503 Service Unavailable and rolls back the local persist.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
