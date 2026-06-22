package com.agiletal.ledger.gateway.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;

    @Column(length = 2000)
    private String metadata;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "applied_to_account", nullable = false)
    private boolean appliedToAccount;

    protected Event() {}

    public Event(String eventId, String accountId, EventType type, BigDecimal amount,
                 String currency, OffsetDateTime eventTimestamp, String metadata,
                 OffsetDateTime receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.receivedAt = receivedAt;
        this.appliedToAccount = false;
    }

    public void markApplied() { this.appliedToAccount = true; }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public EventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getEventTimestamp() { return eventTimestamp; }
    public String getMetadata() { return metadata; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public boolean isAppliedToAccount() { return appliedToAccount; }
}
