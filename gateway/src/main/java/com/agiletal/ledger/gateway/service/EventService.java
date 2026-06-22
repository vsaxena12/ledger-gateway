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
                e.getEventId(), e.getAccountId(), e.getType().name(),
                e.getAmount(), e.getCurrency(), e.getEventTimestamp(),
                null, e.isAppliedToAccount());
    }
}
