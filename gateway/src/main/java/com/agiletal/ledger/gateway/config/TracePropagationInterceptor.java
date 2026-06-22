package com.agiletal.ledger.gateway.config;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class TracePropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId != null && !traceId.isEmpty()) {
            String span = (spanId != null && !spanId.isEmpty()) ? spanId : "0000000000000000";
            request.getHeaders().add("traceparent", "00-" + traceId + "-" + span + "-01");
        }
        return execution.execute(request, body);
    }
}
