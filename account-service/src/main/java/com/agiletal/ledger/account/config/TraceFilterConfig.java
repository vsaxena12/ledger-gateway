package com.agiletal.ledger.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Configuration
public class TraceFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new TraceIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(0);
        reg.setName("traceIdFilter");
        return reg;
    }

    public static class TraceIdFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String traceparent = request.getHeader("traceparent");
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
}
