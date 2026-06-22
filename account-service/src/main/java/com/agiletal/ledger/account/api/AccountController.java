package com.agiletal.ledger.account.api;

import com.agiletal.ledger.account.api.dto.*;
import com.agiletal.ledger.account.domain.Transaction;
import com.agiletal.ledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> apply(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest req) {
        var result = service.apply(accountId, req.eventId(), req.type(), req.amount());
        return ResponseEntity.ok(TransactionResponse.of(result.transaction(), result.balance()));
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, service.getBalance(accountId));
    }

    @GetMapping("/{accountId}")
    public AccountService.AccountView get(@PathVariable String accountId) {
        return service.getAccount(accountId);
    }
}
