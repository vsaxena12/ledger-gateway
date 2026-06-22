package com.agiletal.ledger.account.api;

import com.agiletal.ledger.account.api.dto.*;
import com.agiletal.ledger.account.domain.Transaction;
import com.agiletal.ledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> apply(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest req) {
        log.info("applying {} {} to account {}", req.type(), req.amount(), accountId);
        var result = service.apply(accountId, req.eventId(), req.type(), req.amount());
        log.info("transaction {} applied: balance={}", result.transaction().getEventId(), result.balance());
        return ResponseEntity.ok(TransactionResponse.of(result.transaction(), result.balance()));
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        log.debug("balance query for account {}", accountId);
        return new BalanceResponse(accountId, service.getBalance(accountId));
    }

    @GetMapping("/{accountId}")
    public AccountService.AccountView get(@PathVariable String accountId) {
        log.debug("account details query for {}", accountId);
        return service.getAccount(accountId);
    }
}
