package com.leenex.exam.subscription.promo;

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
 * Discount codes (Phase 11C): a percentage, or a fixed amount in LKR and/or USD, off all plans or chosen plans,
 * between two dates, with limits on uses overall and per student. A use is a payment made with the code that is
 * pending or succeeded; failed and cancelled payments give the use back.
 */
@Repository
public class DiscountCodes {

    public record DiscountCode(UUID id, String code, String description, Integer percentOff, Long amountOffLkrMinor,
                               Long amountOffUsdMinor, boolean allPlans, List<UUID> planIds, OffsetDateTime startsAt,
                               OffsetDateTime endsAt, Integer maxUses, int maxUsesPerStudent, boolean active,
                               int version, int uses) {
    }

    public record CodeData(String description, Integer percentOff, Long amountOffLkrMinor, Long amountOffUsdMinor,
                           boolean allPlans, List<UUID> planIds, OffsetDateTime startsAt, OffsetDateTime endsAt,
                           Integer maxUses, int maxUsesPerStudent, boolean active) {
    }

    private static final String COLUMNS = """
            d.id, d.code, d.description, d.percent_off, d.amount_off_lkr_minor, d.amount_off_usd_minor, d.all_plans,
            d.starts_at, d.ends_at, d.max_uses, d.max_uses_per_student, d.active, d.version,
            (SELECT count(*) FROM payment_transaction t
             WHERE t.discount_code_id = d.id AND t.status IN ('PENDING', 'SUCCEEDED')) AS uses
            """;

    private final JdbcClient jdbc;

    public DiscountCodes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DiscountCode> all() {
        return withPlans(jdbc.sql("SELECT " + COLUMNS + " FROM discount_code d ORDER BY d.created_at DESC LIMIT 500")
                .query(DiscountCodes::row).list());
    }

    public Optional<DiscountCode> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM discount_code d WHERE d.id = :id").param("id", id)
                .query(DiscountCodes::row).optional().map(c -> withPlans(List.of(c)).getFirst());
    }

    public Optional<DiscountCode> byCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM discount_code d WHERE d.code = :c").param("c", code)
                .query(DiscountCodes::row).optional().map(c -> withPlans(List.of(c)).getFirst());
    }

    /** Serialises checkouts using the same code, so its use limits can't be overspent by parallel payments. */
    public void lock(UUID id) {
        jdbc.sql("SELECT id FROM discount_code WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).list();
    }

    public int usesBy(UUID codeId, UUID studentId) {
        return jdbc.sql("""
                SELECT count(*) FROM payment_transaction
                WHERE discount_code_id = :c AND student_id = :s AND status IN ('PENDING', 'SUCCEEDED')
                """).param("c", codeId).param("s", studentId).query(Integer.class).single();
    }

    public boolean codeTaken(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM discount_code WHERE code = :c)").param("c", code)
                .query(Boolean.class).single();
    }

    public void insert(UUID id, String code, CodeData d) {
        jdbc.sql("""
                INSERT INTO discount_code (id, code, description, percent_off, amount_off_lkr_minor, amount_off_usd_minor,
                    all_plans, starts_at, ends_at, max_uses, max_uses_per_student, active)
                VALUES (:id, :code, :description, :percent, :lkr, :usd, :all, :starts, :ends, :max, :perStudent, :active)
                """).param("id", id).param("code", code).params(params(d)).update();
        replacePlans(id, d);
    }

    public boolean update(UUID id, int version, CodeData d) {
        boolean saved = jdbc.sql("""
                UPDATE discount_code SET description = :description, percent_off = :percent,
                    amount_off_lkr_minor = :lkr, amount_off_usd_minor = :usd, all_plans = :all, starts_at = :starts,
                    ends_at = :ends, max_uses = :max, max_uses_per_student = :perStudent, active = :active,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version
                """).param("id", id).param("version", version).params(params(d)).update() == 1;
        if (saved) {
            replacePlans(id, d);
        }
        return saved;
    }

    private void replacePlans(UUID id, CodeData d) {
        jdbc.sql("DELETE FROM discount_code_plan WHERE code_id = :id").param("id", id).update();
        if (!d.allPlans()) {
            for (UUID planId : d.planIds()) {
                jdbc.sql("INSERT INTO discount_code_plan (code_id, plan_id) VALUES (:c, :p)").param("c", id)
                        .param("p", planId).update();
            }
        }
    }

    private static Map<String, Object> params(CodeData d) {
        Map<String, Object> m = new HashMap<>();
        m.put("description", d.description());
        m.put("percent", d.percentOff());
        m.put("lkr", d.amountOffLkrMinor());
        m.put("usd", d.amountOffUsdMinor());
        m.put("all", d.allPlans());
        m.put("starts", d.startsAt());
        m.put("ends", d.endsAt());
        m.put("max", d.maxUses());
        m.put("perStudent", d.maxUsesPerStudent());
        m.put("active", d.active());
        return m;
    }

    private List<DiscountCode> withPlans(List<DiscountCode> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, List<UUID>> plans = new HashMap<>();
        jdbc.sql("SELECT code_id, plan_id FROM discount_code_plan WHERE code_id IN (:ids)")
                .param("ids", rows.stream().map(DiscountCode::id).toList())
                .query((rs, n) -> plans.computeIfAbsent(rs.getObject("code_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getObject("plan_id", UUID.class)))
                .list();
        return rows.stream().map(c -> new DiscountCode(c.id(), c.code(), c.description(), c.percentOff(),
                c.amountOffLkrMinor(), c.amountOffUsdMinor(), c.allPlans(),
                List.copyOf(plans.getOrDefault(c.id(), List.of())), c.startsAt(), c.endsAt(), c.maxUses(),
                c.maxUsesPerStudent(), c.active(), c.version(), c.uses())).toList();
    }

    private static Integer intOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long longOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static DiscountCode row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DiscountCode(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("description"),
                intOrNull(rs, "percent_off"), longOrNull(rs, "amount_off_lkr_minor"),
                longOrNull(rs, "amount_off_usd_minor"), rs.getBoolean("all_plans"), List.of(),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                intOrNull(rs, "max_uses"), rs.getInt("max_uses_per_student"), rs.getBoolean("active"),
                rs.getInt("version"), rs.getInt("uses"));
    }
}
