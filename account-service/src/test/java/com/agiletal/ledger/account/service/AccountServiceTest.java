package com.agiletal.ledger.account.service;

import com.agiletal.ledger.account.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    TransactionRepository txs = mock(TransactionRepository.class);
    AccountService service;

    @BeforeEach
    void setup() { service = new AccountService(accounts, txs); }

    @Test
    void apply_credits_increase_balance() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("150.00"));

        var resp = service.apply("a1", "e1", TransactionType.CREDIT, new BigDecimal("150.00"));

        assertThat(resp.balance()).isEqualByComparingTo("150.00");
        ArgumentCaptor<Transaction> cap = ArgumentCaptor.forClass(Transaction.class);
        verify(txs).save(cap.capture());
        assertThat(cap.getValue().getEventId()).isEqualTo("e1");
        assertThat(cap.getValue().getType()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void apply_debit_decreases_balance() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("50.00"));

        var resp = service.apply("a1", "e1", TransactionType.DEBIT, new BigDecimal("50.00"));
        assertThat(resp.balance()).isEqualByComparingTo("50.00");
    }

    @Test
    void duplicate_event_id_is_idempotent() {
        Transaction existing = new Transaction("e1", "a1", TransactionType.CREDIT,
                new BigDecimal("150.00"), OffsetDateTime.now());
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.findByEventId("e1")).thenReturn(Optional.of(existing));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("150.00"));

        var resp = service.apply("a1", "e1", TransactionType.CREDIT, new BigDecimal("999.99"));
        verify(txs, never()).save(any());
        assertThat(resp.balance()).isEqualByComparingTo("150.00");
    }

    @Test
    void missing_account_is_auto_created() {
        when(accounts.findById("new")).thenReturn(Optional.empty());
        when(accounts.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.findByEventId("e1")).thenReturn(Optional.empty());
        when(txs.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txs.sumForAccount("new")).thenReturn(new BigDecimal("10.00"));

        var resp = service.apply("new", "e1", TransactionType.CREDIT, new BigDecimal("10.00"));
        assertThat(resp.balance()).isEqualByComparingTo("10.00");
    }

    @Test
    void get_account_includes_recent_transactions() {
        when(accounts.findById("a1")).thenReturn(Optional.of(new Account("a1", OffsetDateTime.now())));
        when(txs.sumForAccount("a1")).thenReturn(new BigDecimal("100"));
        when(txs.findTop50ByAccountIdOrderByAppliedAtDesc("a1")).thenReturn(List.of());

        var resp = service.getAccount("a1");
        assertThat(resp.balance()).isEqualByComparingTo("100");
        assertThat(resp.transactions()).isEmpty();
    }
}
