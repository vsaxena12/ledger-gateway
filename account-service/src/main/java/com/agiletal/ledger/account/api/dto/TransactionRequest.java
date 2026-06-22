package com.agiletal.ledger.account.api.dto;

import com.agiletal.ledger.account.domain.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank String currency
) {}
