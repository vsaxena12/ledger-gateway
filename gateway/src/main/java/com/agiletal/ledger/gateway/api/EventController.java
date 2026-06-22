package com.agiletal.ledger.gateway.api;

import com.agiletal.ledger.gateway.api.dto.EventDto;
import com.agiletal.ledger.gateway.service.EventRequest;
import com.agiletal.ledger.gateway.service.EventResponse;
import com.agiletal.ledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventDto dto) {
        EventResponse resp = service.submit(new EventRequest(
                dto.eventId(), dto.accountId(), dto.type(), dto.amount(),
                dto.currency(), dto.eventTimestamp(), dto.metadata()));
        HttpStatus status = resp.appliedToAccount() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(resp);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> get(@PathVariable("id") String id) {
        return service.getByEventId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<EventResponse> list(@RequestParam("account") String accountId) {
        return service.listForAccount(accountId);
    }
}
