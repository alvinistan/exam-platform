package com.leenex.exam.catalog.event;

import com.leenex.exam.catalog.support.Outbox;
import com.leenex.exam.catalog.support.Requests;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox event feed for other services (ADR-006). Service tokens only (platform rule for {@code /internal/**});
 * the gateway never routes it.
 */
@RestController
public class EventFeedController {

    private final Outbox outbox;
    private final JsonMapper json;

    public EventFeedController(Outbox outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public record EventEnvelope(long seq, UUID id, String type, OffsetDateTime occurredAt, JsonNode payload) {
    }

    @GetMapping("/internal/events")
    public List<EventEnvelope> events(@RequestParam long after, @RequestParam(defaultValue = "100") int limit) {
        if (after < 0 || limit < 1 || limit > 500) {
            throw Requests.invalid("limit", "after must be 0 or more and limit between 1 and 500");
        }
        return outbox.after(after, limit).stream()
                .map(e -> new EventEnvelope(e.seq(), e.id(), e.type(), e.occurredAt(), json.readTree(e.payloadJson())))
                .toList();
    }
}
