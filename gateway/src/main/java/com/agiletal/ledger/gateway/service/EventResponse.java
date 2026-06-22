package com.agiletal.ledger.gateway.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp,
        Map<String, Object> metadata,
        boolean appliedToAccount,
        boolean duplicate
) {}
