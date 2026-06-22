package com.agiletal.ledger.account.service;

import com.agiletal.ledger.account.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AccountService {

    public record ApplyResult(String accountId, BigDecimal balance, Transaction transaction) {}
    public record AccountView(String accountId, OffsetDateTime createdAt, BigDecimal balance, List<Transaction> transactions) {}

    private final AccountRepository accounts;
    private final TransactionRepository txs;

    public AccountService(AccountRepository accounts, TransactionRepository txs) {
        this.accounts = accounts;
        this.txs = txs;
    }

    @Transactional
    public ApplyResult apply(String accountId, String eventId, TransactionType type, BigDecimal amount) {
        var existing = txs.findByEventId(eventId);
        if (existing.isPresent()) {
            return new ApplyResult(accountId, txs.sumForAccount(accountId), existing.get());
        }
        accounts.findById(accountId).orElseGet(() ->
                accounts.save(new Account(accountId, OffsetDateTime.now())));
        var tx = new Transaction(eventId, accountId, type, amount, OffsetDateTime.now());
        txs.save(tx);
        return new ApplyResult(accountId, txs.sumForAccount(accountId), tx);
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountId) {
        return txs.sumForAccount(accountId);
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(String accountId) {
        var account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new AccountView(account.getId(), account.getCreatedAt(),
                txs.sumForAccount(accountId),
                txs.findTop50ByAccountIdOrderByAppliedAtDesc(accountId));
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String id) { super("account not found: " + id); }
    }
}
