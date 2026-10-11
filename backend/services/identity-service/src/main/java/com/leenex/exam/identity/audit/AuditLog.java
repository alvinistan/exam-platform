package com.leenex.exam.identity.audit;

import com.leenex.exam.platform.web.CorrelationIdFilter;
import com.leenex.exam.platform.web.UuidV7;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only record of security-relevant actions (FR-ID-08, FR-ADM-04). Writes join the caller's transaction,
 * so an action and its audit row are saved together or not at all. Never record tokens or personal data beyond IDs.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorId, String action, String targetType, Object targetId, Map<String, ?> summary) {
        jdbc.sql("""
                INSERT INTO audit_event (id, actor_id, action, target_type, target_id, summary, correlation_id)
                VALUES (:id, :actor, :action, :targetType, :targetId, CAST(:summary AS jsonb), :correlationId)
                """)
                .param("id", UuidV7.generate())
                .param("actor", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId == null ? null : targetId.toString())
                .param("summary", json.writeValueAsString(summary == null ? Map.of() : summary))
                .param("correlationId", CorrelationIdFilter.currentId())
                .update();
    }
}
