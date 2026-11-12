package com.leenex.exam.subscription.promo;

import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.billing.Plans.Limits;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Free access campaigns (Phase 11C). ALL: every signed-in student has the access between the campaign's dates (their
 * entitlement row appears when they first use it). SELECTED: chosen students receive an entitlement straight away,
 * for the campaign's length. Either way access flows through the same entitlements as paid plans.
 */
@Repository
public class Campaigns {

    public record Campaign(UUID id, String name, String scopeType, UUID categoryId, List<UUID> examIds, Limits limits,
                           String audience, OffsetDateTime startsAt, OffsetDateTime endsAt, Integer durationMonths,
                           OffsetDateTime endedAt, UUID createdBy, OffsetDateTime createdAt, int students) {

        /** Open to every student right now. */
        public boolean openToAllAt(java.time.Instant now) {
            return "ALL".equals(audience) && endedAt == null && !now.isBefore(startsAt.toInstant())
                    && now.isBefore(endsAt.toInstant());
        }
    }

    private static final String COLUMNS = """
            c.id, c.name, c.scope_type, c.category_id, c.limit_practice, c.limit_mock, c.limit_adaptive, c.audience,
            c.starts_at, c.ends_at, c.duration_months, c.ended_at, c.created_by, c.created_at,
            (SELECT count(*) FROM entitlement e WHERE e.campaign_id = c.id) AS students
            """;

    private final JdbcClient jdbc;

    public Campaigns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Campaign> all() {
        return withExams(jdbc.sql("SELECT " + COLUMNS + " FROM campaign c ORDER BY c.created_at DESC LIMIT 200")
                .query(Campaigns::row).list());
    }

    public Optional<Campaign> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM campaign c WHERE c.id = :id").param("id", id)
                .query(Campaigns::row).optional().map(c -> withExams(List.of(c)).getFirst());
    }

    /** Campaigns open to every student at {@code now}. */
    public List<Campaign> openToAll(OffsetDateTime now) {
        return withExams(jdbc.sql("SELECT " + COLUMNS + """
                FROM campaign c
                WHERE c.audience = 'ALL' AND c.ended_at IS NULL AND c.starts_at <= :now AND c.ends_at > :now
                ORDER BY c.ends_at
                """).param("now", now).query(Campaigns::row).list());
    }

    public void insert(UUID id, String name, String scopeType, UUID categoryId, List<UUID> examIds, Limits limits,
                       String audience, OffsetDateTime startsAt, OffsetDateTime endsAt, Integer durationMonths,
                       UUID createdBy) {
        jdbc.sql("""
                INSERT INTO campaign (id, name, scope_type, category_id, limit_practice, limit_mock, limit_adaptive,
                    audience, starts_at, ends_at, duration_months, created_by)
                VALUES (:id, :name, :scope, :category, :practice, :mock, :adaptive, :audience, :starts, :ends, :months,
                    :by)
                """)
                .param("id", id).param("name", name).param("scope", scopeType).param("category", categoryId)
                .param("practice", limits.practice()).param("mock", limits.mock()).param("adaptive", limits.adaptive())
                .param("audience", audience).param("starts", startsAt).param("ends", endsAt)
                .param("months", durationMonths == null ? null : durationMonths.shortValue()).param("by", createdBy)
                .update();
        for (UUID examId : examIds) {
            jdbc.sql("INSERT INTO campaign_exam (campaign_id, exam_id) VALUES (:c, :e)").param("c", id)
                    .param("e", examId).update();
        }
    }

    /** Ends a campaign now: it closes to everyone, and the access it already gave stops (revoked, with a reason). */
    public int end(UUID id) {
        jdbc.sql("UPDATE campaign SET ended_at = now() WHERE id = :id AND ended_at IS NULL").param("id", id).update();
        return jdbc.sql("""
                UPDATE entitlement SET revoked_at = now(), revoked_reason = 'Campaign ended'
                WHERE campaign_id = :id AND revoked_at IS NULL
                """).param("id", id).update();
    }

    /**
     * Gives a student the campaign's access between {@code startsAt} and {@code endsAt}; nothing happens if they
     * already have it. Returns the student's entitlement for this campaign.
     */
    public UUID grant(Campaign c, UUID studentId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID id = UuidV7.generate();
        int inserted = jdbc.sql("""
                INSERT INTO entitlement (id, student_id, source, campaign_id, starts_at, ends_at, plan_name, scope_type,
                    limit_practice, limit_mock, limit_adaptive, granted_by, grant_reason)
                VALUES (:id, :student, 'CAMPAIGN', :campaign, :starts, :ends, :name, :scope, :practice, :mock,
                    :adaptive, :by, :reason)
                ON CONFLICT (campaign_id, student_id) DO NOTHING
                """)
                .param("id", id).param("student", studentId).param("campaign", c.id()).param("starts", startsAt)
                .param("ends", endsAt).param("name", c.name()).param("scope", c.scopeType())
                .param("practice", c.limits().practice()).param("mock", c.limits().mock())
                .param("adaptive", c.limits().adaptive()).param("by", c.createdBy())
                .param("reason", "Campaign: " + c.name())
                .update();
        if (inserted == 1) {
            for (UUID examId : c.examIds()) {
                jdbc.sql("INSERT INTO entitlement_exam (entitlement_id, exam_id) VALUES (:e, :x)").param("e", id)
                        .param("x", examId).update();
            }
            return id;
        }
        return jdbc.sql("SELECT id FROM entitlement WHERE campaign_id = :c AND student_id = :s")
                .param("c", c.id()).param("s", studentId).query(UUID.class).single();
    }

    private List<Campaign> withExams(List<Campaign> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, List<UUID>> exams = new HashMap<>();
        jdbc.sql("SELECT campaign_id, exam_id FROM campaign_exam WHERE campaign_id IN (:ids)")
                .param("ids", rows.stream().map(Campaign::id).toList())
                .query((rs, n) -> exams.computeIfAbsent(rs.getObject("campaign_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getObject("exam_id", UUID.class)))
                .list();
        return rows.stream().map(c -> new Campaign(c.id(), c.name(), c.scopeType(), c.categoryId(),
                List.copyOf(exams.getOrDefault(c.id(), List.of())), c.limits(), c.audience(), c.startsAt(), c.endsAt(),
                c.durationMonths(), c.endedAt(), c.createdBy(), c.createdAt(), c.students())).toList();
    }

    private static Integer intOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Campaign row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Campaign(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("scope_type"),
                rs.getObject("category_id", UUID.class), List.of(),
                new Limits(intOrNull(rs, "limit_practice"), intOrNull(rs, "limit_mock"), intOrNull(rs, "limit_adaptive")),
                rs.getString("audience"), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), intOrNull(rs, "duration_months"),
                rs.getObject("ended_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getInt("students"));
    }
}
