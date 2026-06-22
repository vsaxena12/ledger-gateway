package com.agiletal.ledger.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByEventId(String eventId);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.type = com.agiletal.ledger.account.domain.TransactionType.CREDIT
                                 THEN t.amount ELSE -t.amount END), 0)
        FROM Transaction t WHERE t.accountId = :accountId
    """)
    BigDecimal sumForAccount(@Param("accountId") String accountId);

    List<Transaction> findTop50ByAccountIdOrderByAppliedAtDesc(String accountId);
}
