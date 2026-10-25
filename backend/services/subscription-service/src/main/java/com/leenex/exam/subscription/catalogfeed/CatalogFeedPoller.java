package com.leenex.exam.subscription.catalogfeed;

import com.leenex.exam.subscription.access.AccessRules;
import com.leenex.exam.subscription.catalogfeed.CatalogFeedClient.FeedEvent;
import com.leenex.exam.subscription.config.SubscriptionProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Keeps {@code exam_view} in step with the catalogue (ADR-006): reads events after the stored cursor, applies each
 * once (processed_event), and moves the cursor in the same transaction. At-least-once delivery is safe because
 * applying an event is an upsert of the exam's latest state.
 */
@Component
public class CatalogFeedPoller {

    static final String SOURCE = "catalog";
    private static final Logger log = LoggerFactory.getLogger(CatalogFeedPoller.class);
    private static final Set<String> EXAM_EVENTS = Set.of("ExamPublished", "ExamUpdated", "ExamArchived", "ExamRestored");

    private final CatalogFeedClient feed;
    private final ExamViewRepository exams;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private int failures;

    public CatalogFeedPoller(CatalogFeedClient feed, ExamViewRepository exams, JdbcClient jdbc, TransactionTemplate tx,
                             SubscriptionProperties properties) {
        this.enabled = properties.catalogFeed().enabled();
        this.feed = feed;
        this.exams = exams;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Reads and applies one batch; returns how many events were read (0 when the feed is drained). */
    public int pollOnce() {
        long cursor = jdbc.sql("SELECT last_seq FROM feed_cursor WHERE source = :s").param("s", SOURCE)
                .query(Long.class).optional().orElse(0L);
        List<FeedEvent> events = feed.after(cursor);
        if (events.isEmpty()) {
            return 0;
        }
        tx.executeWithoutResult(status -> {
            for (FeedEvent event : events) {
                boolean fresh = jdbc.sql("""
                        INSERT INTO processed_event (event_id, source) VALUES (:id, :s) ON CONFLICT DO NOTHING
                        """).param("id", event.id()).param("s", SOURCE).update() == 1;
                if (fresh && EXAM_EVENTS.contains(event.type())) {
                    apply(event);
                }
            }
            jdbc.sql("""
                    INSERT INTO feed_cursor (source, last_seq, updated_at) VALUES (:s, :seq, now())
                    ON CONFLICT (source) DO UPDATE SET last_seq = EXCLUDED.last_seq, updated_at = now()
                    """).param("s", SOURCE).param("seq", events.getLast().seq()).update();
        });
        return events.size();
    }

    private void apply(FeedEvent event) {
        JsonNode p = event.payload();
        JsonNode order = p.get("displayOrder");
        exams.upsert(UUID.fromString(p.get("examId").asString()), UUID.fromString(p.get("categoryId").asString()),
                text(p, "title"), text(p, "slug"), order == null || order.isNull() ? null : order.asInt(),
                p.get("status").asString(), event.occurredAt(), modes(p), freeAttempts(p));
    }

    private static List<String> modes(JsonNode p) {
        List<String> modes = new ArrayList<>();
        JsonNode node = p.get("modes");
        if (node != null && node.isArray()) {
            node.forEach(m -> modes.add(m.asString()));
        }
        return modes;
    }

    /** Older events (before Phase 9) carry no free attempts; those exams then have none until they change. */
    private static Map<String, Integer> freeAttempts(JsonNode p) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        JsonNode node = p.get("freeAttempts");
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> raw.put(e.getKey(), e.getValue().asInt()));
        }
        return AccessRules.cleanFreeAttempts(raw);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Polls until the feed is drained; a failure is logged and retried on the next tick. */
    @Scheduled(fixedDelayString = "${subscription.catalog-feed.interval:2s}", initialDelayString = "5s")
    public void scheduledPoll() {
        if (!enabled) {
            return;
        }
        try {
            while (pollOnce() > 0) {
                // keep going while there is a backlog
            }
            failures = 0;
        } catch (RuntimeException e) {
            // Log the first failure and then every 30th, so an outage doesn't flood the logs.
            if (failures++ % 30 == 0) {
                log.warn("Catalogue feed poll failed ({} in a row): {}", failures, e.getMessage());
            }
        }
    }
}
