package com.agiletal.ledger.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(0)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String TRACEPARENT_HEADER = "traceparent";
    static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        String traceId;
        String spanId;

        if (traceparent != null && traceparent.startsWith("00-")) {
            String[] parts = traceparent.split("-");
            traceId = parts.length > 1 ? parts[1] : UUID.randomUUID().toString().replace("-", "");
            spanId = parts.length > 2 ? parts[2] : generateSpanId();
        } else {
            traceId = UUID.randomUUID().toString().replace("-", "");
            spanId = generateSpanId();
        }

        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);
        response.setHeader(X_TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
