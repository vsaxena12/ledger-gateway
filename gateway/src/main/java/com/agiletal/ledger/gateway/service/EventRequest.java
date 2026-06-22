package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventRequest(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp,
        Map<String, Object> metadata
) {}
