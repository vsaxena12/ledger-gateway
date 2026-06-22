package com.agiletal.ledger.account.api.dto;

import com.agiletal.ledger.account.domain.Transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
        Long id,
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        OffsetDateTime appliedAt,
        BigDecimal balance
) {
    public static TransactionResponse of(Transaction t, BigDecimal balance) {
        return new TransactionResponse(t.getId(), t.getEventId(), t.getAccountId(),
                t.getType().name(), t.getAmount(), t.getAppliedAt(), balance);
    }
}
