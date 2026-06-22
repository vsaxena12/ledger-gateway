package com.agiletal.ledger.gateway.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByEventId(String eventId);
    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
    List<Event> findByAppliedToAccountAndRetryCountLessThan(boolean appliedToAccount, int retryCount);
}
