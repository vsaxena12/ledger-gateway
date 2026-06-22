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
class EndToEndIntegrationTest {

    // NOTE: this test assumes account-service is running on :18081.
    // Use docker compose up account-service before running, OR pair with
    // the dedicated profile.
    @Autowired ApplicationContext context;
    @Autowired RestClient.Builder restClientBuilder;

    @Test
    void post_event_propagates_to_account_service_balance() {
        LocalTestWebServer server = LocalTestWebServer.get(context);
        String body = """
            {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT","amount":250,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}""";
        ResponseEntity<String> resp = restClientBuilder.build()
                .post()
                .uri(server.uri("/events"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
