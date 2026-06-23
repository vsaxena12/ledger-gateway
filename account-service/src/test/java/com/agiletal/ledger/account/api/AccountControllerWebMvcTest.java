package com.agiletal.ledger.account.api;

import com.agiletal.ledger.account.api.dto.TransactionRequest;
import com.agiletal.ledger.account.domain.TransactionType;
import com.agiletal.ledger.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService service;

    @Test
    void applyTransactionReturns200() throws Exception {
        when(service.apply(eq("acct-1"), eq("evt-1"), eq(TransactionType.CREDIT), any()))
                .thenReturn(new AccountService.ApplyResult("acct-1", new BigDecimal("100.00"),
                        new com.agiletal.ledger.account.domain.Transaction(
                                "evt-1", "acct-1", TransactionType.CREDIT,
                                new BigDecimal("100.00"), OffsetDateTime.now())));

        mvc.perform(post("/accounts/acct-1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"eventId":"evt-1","type":"CREDIT","amount":100.00,"currency":"USD"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void getBalanceReturns200() throws Exception {
        when(service.getBalance("acct-1")).thenReturn(new BigDecimal("250.00"));
        mvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void getAccountReturns200() throws Exception {
        var view = new AccountService.AccountView(
                "acct-1", OffsetDateTime.now(), new BigDecimal("100.00"), java.util.List.of());
        when(service.getAccount("acct-1")).thenReturn(view);
        mvc.perform(get("/accounts/acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void invalidTransactionReturns400() throws Exception {
        mvc.perform(post("/accounts/acct-1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"eventId":"","type":"INVALID","amount":0}
                """))
                .andExpect(status().isBadRequest());
    }
}
