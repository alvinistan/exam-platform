package com.leenex.exam.catalog.support;

import com.leenex.exam.platform.web.UuidV7;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox (ADR-006): domain events are written in the same transaction as the change they describe and
 * read by other services through {@code GET /internal/events}. Consumers track the last sequence number they saw.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final double visibleAfterSeconds;

    /**
     * @param visibleAfter the feed only returns events at least this old (ADR-006): a transaction that started
     *                     earlier but commits later can't then be skipped by a consumer that already moved on
     */
    public Outbox(JdbcClient jdbc, JsonMapper json,
                  @org.springframework.beans.factory.annotation.Value("${catalog.outbox.visible-after:5s}")
                  java.time.Duration visibleAfter) {
        this.jdbc = jdbc;
        this.json = json;
        this.visibleAfterSeconds = visibleAfter.toMillis() / 1000.0;
    }

    public void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload) {
        jdbc.sql("""
                INSERT INTO catalog.outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:id, :type, :aggregateId, :eventType, CAST(:payload AS jsonb))
                """)
                .param("id", UuidV7.generate())
                .param("type", aggregateType)
                .param("aggregateId", aggregateId.toString())
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }

    public record StoredEvent(long seq, UUID id, String type, OffsetDateTime occurredAt, String payloadJson) {
    }

    public List<StoredEvent> after(long seq, int limit) {
        return jdbc.sql("""
                SELECT seq, id, event_type, occurred_at, payload::text AS payload
                FROM catalog.outbox_event
                WHERE seq > :after AND occurred_at <= now() - make_interval(secs => :delay)
                ORDER BY seq LIMIT :limit
                """)
                .param("after", seq).param("limit", limit).param("delay", visibleAfterSeconds)
                .query((rs, row) -> new StoredEvent(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getString("event_type"), rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("payload")))
                .list();
    }
}
