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

    /**
     * What one entitlement allows: its own copy of the plan's scope and limits (Phase 11B). {@code examIds} is empty
     * for scope ALL.
     */
    public record Grant(UUID id, String source, String planName, String scopeType, List<UUID> examIds,
                        com.leenex.exam.subscription.billing.Plans.Limits limits, OffsetDateTime startsAt,
                        OffsetDateTime endsAt) {

        public boolean covers(UUID examId) {
            return "ALL".equals(scopeType) || examIds.contains(examId);
        }
    }

    /** The student's entitlements active at {@code now}, soonest-ending first. Never cached (FR-SUB-08). */
    public List<Grant> activeGrants(UUID studentId, Instant now) {
        java.util.Map<UUID, List<UUID>> exams = new java.util.HashMap<>();
        OffsetDateTime at = OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
        jdbc.sql("""
                SELECT ee.entitlement_id, ee.exam_id FROM entitlement_exam ee JOIN entitlement e ON e.id = ee.entitlement_id
                WHERE e.student_id = :s AND e.revoked_at IS NULL AND e.starts_at <= :now AND e.ends_at > :now
                """).param("s", studentId).param("now", at)
                .query((rs, n) -> exams.computeIfAbsent(rs.getObject("entitlement_id", UUID.class),
                        k -> new java.util.ArrayList<>()).add(rs.getObject("exam_id", UUID.class)))
                .list();
        return jdbc.sql("""
                SELECT id, source, plan_name, scope_type, limit_practice, limit_mock, limit_adaptive, starts_at, ends_at
                FROM entitlement
                WHERE student_id = :s AND revoked_at IS NULL AND starts_at <= :now AND ends_at > :now
                ORDER BY ends_at, id
                """).param("s", studentId).param("now", at)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Grant(id, rs.getString("source"), rs.getString("plan_name"), rs.getString("scope_type"),
                            List.copyOf(exams.getOrDefault(id, List.of())),
                            new com.leenex.exam.subscription.billing.Plans.Limits(intOrNull(rs, "limit_practice"),
                                    intOrNull(rs, "limit_mock"), intOrNull(rs, "limit_adaptive")),
                            rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class));
                }).list();
    }

    /** Key for counting attempts started on an entitlement. */
    public record UseKey(UUID entitlementId, UUID examId, String mode) {
    }

    /** Attempts counted against the given entitlements, per entitlement, exam and mode. */
    public java.util.Map<UseKey, Integer> usage(List<UUID> entitlementIds) {
        java.util.Map<UseKey, Integer> used = new java.util.HashMap<>();
        if (entitlementIds.isEmpty()) {
            return used;
        }
        jdbc.sql("""
                SELECT entitlement_id, exam_id, mode, count(*) AS n FROM entitlement_use
                WHERE entitlement_id IN (:ids) GROUP BY entitlement_id, exam_id, mode
                """).param("ids", entitlementIds)
                .query((rs, n) -> used.put(new UseKey(rs.getObject("entitlement_id", UUID.class),
                        rs.getObject("exam_id", UUID.class), rs.getString("mode")), rs.getInt("n")))
                .list();
        return used;
    }

    /** The entitlement an attempt was already counted against, if any (a retried start counts once). */
    public Optional<UUID> usedFor(UUID attemptId, UUID studentId) {
        return jdbc.sql("SELECT entitlement_id FROM entitlement_use WHERE attempt_id = :a AND student_id = :s")
                .param("a", attemptId).param("s", studentId).query(UUID.class).optional();
    }

    public void recordUse(UUID attemptId, UUID entitlementId, UUID studentId, UUID examId, String mode) {
        jdbc.sql("""
                INSERT INTO entitlement_use (attempt_id, entitlement_id, student_id, exam_id, mode)
                VALUES (:a, :e, :s, :x, :m) ON CONFLICT DO NOTHING
                """).param("a", attemptId).param("e", entitlementId).param("s", studentId).param("x", examId)
                .param("m", mode).update();
    }

    private static Integer intOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
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
