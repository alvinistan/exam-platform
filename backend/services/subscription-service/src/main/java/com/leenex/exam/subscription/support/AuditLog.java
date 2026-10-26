package com.leenex.exam.subscription.support;

import com.leenex.exam.platform.web.CorrelationIdFilter;
import com.leenex.exam.platform.web.UuidV7;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Append-only record of policy and entitlement changes (FR-ADM-04). Joins the caller's transaction. */
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
