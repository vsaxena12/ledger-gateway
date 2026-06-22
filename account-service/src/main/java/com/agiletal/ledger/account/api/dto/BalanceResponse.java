package com.agiletal.ledger.account.api.dto;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance) {}
