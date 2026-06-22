package com.agiletal.ledger.gateway.api;

import com.agiletal.ledger.gateway.service.EventResponse;
import com.agiletal.ledger.gateway.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class GatewayControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @MockitoBean EventService service;

    @Test
    void post_event_201_on_new() throws Exception {
        when(service.submit(any())).thenReturn(new EventResponse(
                "e1", "a1", "CREDIT", new BigDecimal("100"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null, true, false));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("e1"));
    }

    @Test
    void post_event_200_on_duplicate() throws Exception {
        when(service.submit(any())).thenReturn(new EventResponse(
                "e1", "a1", "CREDIT", new BigDecimal("100"), "USD",
                OffsetDateTime.parse("2026-05-15T14:02:11Z"), null, false, true));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"e1","accountId":"a1","type":"CREDIT","amount":100,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isOk());
    }

    @Test
    void post_event_400_on_validation_error() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
            {"eventId":"","accountId":"a1","type":"CREDIT","amount":-5,
             "currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}
        """)).andExpect(status().isBadRequest());
    }

    @Test
    void get_event_by_id_404_when_missing() throws Exception {
        when(service.getByEventId("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/events/nope")).andExpect(status().isNotFound());
    }

    @Test
    void get_events_by_account_returns_list() throws Exception {
        when(service.listForAccount("a1")).thenReturn(List.of());
        mvc.perform(get("/events?account=a1")).andExpect(status().isOk());
    }
}