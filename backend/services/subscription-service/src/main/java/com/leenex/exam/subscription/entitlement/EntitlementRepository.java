package com.leenex.exam.subscription.entitlement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Entitlements: what grants premium access (FA-7). Active means not revoked and now within [starts_at, ends_at). */
@Repository
public class EntitlementRepository {

    public record EntitlementRow(UUID id, UUID studentId, String source, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                 OffsetDateTime revokedAt, String revokedReason, String grantReason, UUID grantedBy,
                                 OffsetDateTime createdAt) {

        public String state(Instant now) {
            if (revokedAt != null) {
                return "REVOKED";
            }
            if (now.isBefore(startsAt.toInstant())) {
                return "SCHEDULED";
            }
            return now.isBefore(endsAt.toInstant()) ? "ACTIVE" : "EXPIRED";
        }
    }

    private static final String COLUMNS = """
            id, student_id, source, starts_at, ends_at, revoked_at, revoked_reason, grant_reason, granted_by, created_at
            """;

    private final JdbcClient jdbc;

    public EntitlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active entitlement that ends last, if any. Checked on every request; never cached (FR-SUB-08). */
    public Optional<EntitlementRow> activeFor(UUID studentId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM entitlement
                WHERE student_id = :student AND revoked_at IS NULL AND starts_at <= :now AND ends_at > :now
                ORDER BY ends_at DESC LIMIT 1
                """)
                .param("student", studentId).param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .query(EntitlementRepository::row).optional();
    }

    public List<EntitlementRow> list(UUID studentId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entitlement WHERE (CAST(:student AS uuid) IS NULL OR student_id = :student) "
                        + "ORDER BY created_at DESC LIMIT :limit")
                .param("student", studentId).param("limit", limit)
                .query(EntitlementRepository::row).list();
    }

    public Optional<EntitlementRow> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entitlement WHERE id = :id FOR UPDATE")
                .param("id", id).query(EntitlementRepository::row).optional();
    }

    public void insertManual(UUID id, UUID studentId, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason,
                             UUID grantedBy) {
        jdbc.sql("""
                INSERT INTO entitlement (id, student_id, source, starts_at, ends_at, grant_reason, granted_by)
                VALUES (:id, :student, 'MANUAL', :starts, :ends, :reason, :by)
                """)
                .param("id", id).param("student", studentId).param("starts", startsAt).param("ends", endsAt)
                .param("reason", reason).param("by", grantedBy)
                .update();
    }

    public void revoke(UUID id, String reason) {
        jdbc.sql("UPDATE entitlement SET revoked_at = now(), revoked_reason = :reason WHERE id = :id")
                .param("id", id).param("reason", reason).update();
    }

    private static EntitlementRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new EntitlementRow(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getString("source"), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getString("revoked_reason"), rs.getString("grant_reason"), rs.getObject("granted_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
