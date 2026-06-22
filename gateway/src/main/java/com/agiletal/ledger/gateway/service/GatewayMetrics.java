package com.agiletal.ledger.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GatewayMetrics {

    private final Counter eventsReceived;
    private final Counter eventsDuplicate;
    private final Counter accountServiceCalls;
    private final Counter accountServiceFailures;
    private final Timer accountServiceLatency;

    public GatewayMetrics(MeterRegistry registry) {
        this.eventsReceived = Counter.builder("gateway.events.received")
                .description("Total events received")
                .tag("result", "created")
                .register(registry);
        this.eventsDuplicate = Counter.builder("gateway.events.received")
                .description("Duplicate events detected")
                .tag("result", "duplicate")
                .register(registry);
        this.accountServiceCalls = Counter.builder("gateway.account_service.calls")
                .description("Total calls to account service")
                .tag("outcome", "success")
                .register(registry);
        this.accountServiceFailures = Counter.builder("gateway.account_service.calls")
                .description("Failed calls to account service")
                .tag("outcome", "failure")
                .register(registry);
        this.accountServiceLatency = Timer.builder("gateway.account_service.latency")
                .description("Account service call latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public Counter getEventsReceived() { return eventsReceived; }
    public Counter getEventsDuplicate() { return eventsDuplicate; }
    public Counter getAccountServiceCalls() { return accountServiceCalls; }
    public Counter getAccountServiceFailures() { return accountServiceFailures; }
    public Timer getAccountServiceLatency() { return accountServiceLatency; }
}
