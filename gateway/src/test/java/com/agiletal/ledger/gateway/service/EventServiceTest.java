package com.agiletal.ledger.gateway.service;

import com.agiletal.ledger.gateway.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventServiceTest {

    EventRepository repo = mock(EventRepository.class);
    AccountClient client = mock(AccountClient.class);
    EventService service;

    @BeforeEach
    void setup() { service = new EventService(repo, client); }

    private EventRequest req(String eid) {
        return new EventRequest(eid, "acct-1", EventType.CREDIT,
                new BigDecimal("100.00"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"),
                Map.of("source", "test"));
    }

    @Test
    void submit_new_event_persists_and_calls_account_service() {
        when(repo.findByEventId("e1")).thenReturn(Optional.empty());
        when(repo.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(client.apply(eq("acct-1"), eq("e1"), eq("CREDIT"),
                any(), any(), any()))
                .thenReturn(new AccountClient.ApplyResult(1L, "e1", "acct-1",
                        "CREDIT", new BigDecimal("100.00"),
                        OffsetDateTime.now(), new BigDecimal("100.00")));

        var resp = service.submit(req("e1"));

        assertThat(resp.eventId()).isEqualTo("e1");
        assertThat(resp.type()).isEqualTo("CREDIT");
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().isAppliedToAccount()).isTrue();
    }

    @Test
    void duplicate_eventId_returns_existing_without_calling_account_service() {
        Event existing = new Event("e1", "acct-1", EventType.CREDIT,
                new BigDecimal("100.00"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null,
                OffsetDateTime.now());
        when(repo.findByEventId("e1")).thenReturn(Optional.of(existing));

        var resp = service.submit(req("e1"));

        assertThat(resp.eventId()).isEqualTo("e1");
        verifyNoInteractions(client);
    }

    @Test
    void account_service_failure_bubbles_up_and_event_persists() {
        when(repo.findByEventId("e1")).thenReturn(Optional.empty());
        when(repo.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(client.apply(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AccountServiceUnavailableException("down"));

        assertThatThrownBy(() -> service.submit(req("e1")))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void list_for_account_orders_by_event_timestamp() {
        Event e1 = new Event("e1", "acct-1", EventType.CREDIT, new BigDecimal("1"),
                "USD", OffsetDateTime.parse("2026-05-15T10:00:00Z"), null, OffsetDateTime.now());
        Event e2 = new Event("e2", "acct-1", EventType.CREDIT, new BigDecimal("2"),
                "USD", OffsetDateTime.parse("2026-05-15T09:00:00Z"), null, OffsetDateTime.now());
        when(repo.findByAccountIdOrderByEventTimestampAsc("acct-1")).thenReturn(List.of(e2, e1));

        var list = service.listForAccount("acct-1");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).eventId()).isEqualTo("e2");
        assertThat(list.get(1).eventId()).isEqualTo("e1");
    }

    @Test
    void get_by_event_id_returns_existing() {
        Event e = new Event("e1", "acct-1", EventType.CREDIT, new BigDecimal("1"),
                "USD", OffsetDateTime.now(), null, OffsetDateTime.now());
        when(repo.findByEventId("e1")).thenReturn(Optional.of(e));
        assertThat(service.getByEventId("e1")).isPresent();
    }
}
