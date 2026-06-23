package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.Event;
import com.agiletal.ledger.gateway.domain.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository repo;
    private final AccountClient accountClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository repo, AccountClient accountClient,
                        MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.repo = repo;
        this.accountClient = accountClient;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EventResponse submit(EventRequest req) {
        var existing = repo.findByEventId(req.eventId());
        if (existing.isPresent()) {
            log.info("duplicate event {} - returning original", req.eventId());
            meterRegistry.counter("gateway.events.received", "result", "duplicate").increment();
            return toResponse(existing.get(), true);
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
            meterRegistry.counter("gateway.events.received", "result", "created").increment();
        } catch (AccountServiceUnavailableException ex) {
            log.warn("account-service unavailable for event {}; queued for async retry",
                    req.eventId());
            // Event stays persisted with appliedToAccount=false for async retry
        }
        return toResponse(event, false);
    }

    @Scheduled(fixedDelay = 30000) // every 30 seconds
    @Transactional
    public void retryPendingEvents() {
        List<Event> pending = repo.findByAppliedToAccountAndRetryCountLessThan(false, 5);
        for (Event event : pending) {
            try {
                accountClient.apply(event.getAccountId(), event.getEventId(),
                        event.getType().name(), event.getAmount(), event.getCurrency(),
                        event.getEventTimestamp());
                event.markApplied();
                repo.save(event);
                log.info("async retry succeeded for event {}", event.getEventId());
                meterRegistry.counter("gateway.events.async_retry", "outcome", "success").increment();
            } catch (Exception ex) {
                log.warn("async retry failed for event {} (attempt {})", event.getEventId(), event.getRetryCount() + 1);
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastRetryAt(OffsetDateTime.now());
                repo.save(event);
                meterRegistry.counter("gateway.events.async_retry", "outcome", "failure").increment();
            }
        }
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
        return toResponse(e, false);
    }

    private EventResponse toResponse(Event e, boolean duplicate) {
        return new EventResponse(
                e.getEventId(), e.getAccountId(), e.getType().name(),
                e.getAmount(), e.getCurrency(), e.getEventTimestamp(),
                parseMetadata(e.getMetadata()), e.isAppliedToAccount(), duplicate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception ex) {
            log.warn("failed to parse metadata: {}", ex.getMessage());
            return null;
        }
    }
}
