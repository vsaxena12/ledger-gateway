package com.agiletal.ledger.gateway.api.dto;

import com.agiletal.ledger.gateway.domain.EventType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventDto(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 64) String accountId,
        @NotNull EventType type,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Size(max = 8) String currency,
        @NotNull OffsetDateTime eventTimestamp,
        Map<String, Object> metadata
) {}
