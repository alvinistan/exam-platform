package com.leenex.exam.subscription.billing;

import java.math.BigDecimal;
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
 * Plans the administrator sells (FR-SUB-01, Phase 11B): one exam, a fixed bundle of exams, or everything; an LKR and a
 * USD price (either may be absent); and attempt limits per exam type. Prices are in the currency's smallest unit.
 */
@Repository
public class Plans {

    public static final List<String> CURRENCIES = List.of("LKR", "USD");

    /**
     * Attempts per exam type, per exam, for the length of a plan: null = unlimited, 0 = not included.
     */
    public record Limits(Integer practice, Integer mock, Integer adaptive) {

        public static final Limits UNLIMITED = new Limits(null, null, null);

        public Integer forMode(String mode) {
            return switch (mode) {
                case "PRACTICE" -> practice;
                case "MOCK" -> mock;
                case "USER_FRIENDLY" -> adaptive;
                default -> 0;
            };
        }
    }

    /** A lower price between two dates (introductory offers); either currency may be absent. */
    public record Sale(Long priceLkrMinor, Long priceUsdMinor, OffsetDateTime startsAt, OffsetDateTime endsAt) {

        public boolean onAt(java.time.Instant now) {
            return startsAt != null && endsAt != null && !now.isBefore(startsAt.toInstant())
                    && now.isBefore(endsAt.toInstant());
        }

        public Long priceMinor(String currency) {
            return "USD".equals(currency) ? priceUsdMinor : "LKR".equals(currency) ? priceLkrMinor : null;
        }
    }

    public record Plan(UUID id, String code, String name, String description, String scopeType, UUID categoryId,
                       List<UUID> examIds, int durationMonths, Long priceLkrMinor, Long priceUsdMinor, Limits limits,
                       boolean active, int displayOrder, int version, OffsetDateTime updatedAt, Sale sale) {

        public Long priceMinor(String currency) {
            return "USD".equals(currency) ? priceUsdMinor : "LKR".equals(currency) ? priceLkrMinor : null;
        }

        /** The price to charge now: the sale price while the sale is on (and set for that currency), else the price. */
        public Long priceNow(String currency, java.time.Instant now) {
            Long regular = priceMinor(currency);
            if (regular == null || sale == null || !sale.onAt(now)) {
                return regular;
            }
            Long onSale = sale.priceMinor(currency);
            return onSale != null && onSale < regular ? onSale : regular;
        }

        /** Whether the plan includes this exam (an EXAM or BUNDLE plan lists it, or the plan covers everything). */
        public boolean covers(UUID examId) {
            return "ALL".equals(scopeType) || examIds.contains(examId);
        }
    }

    /** "4500.00": the amount PayHere expects (two decimals, a dot, no thousands separator). */
    public static String amount(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static final String COLUMNS = """
            id, code, name, description, scope_type, category_id, duration_months, price_lkr_minor, price_usd_minor,
            limit_practice, limit_mock, limit_adaptive, active, display_order, version, updated_at,
            sale_price_lkr_minor, sale_price_usd_minor, sale_starts_at, sale_ends_at
            """;

    private final JdbcClient jdbc;

    public Plans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> all(boolean activeOnly) {
        List<Plan> rows = jdbc.sql("SELECT " + COLUMNS + " FROM subscription_plan" + (activeOnly ? " WHERE active" : "")
                        + " ORDER BY display_order, duration_months, code")
                .query((rs, n) -> row(rs, List.of())).list();
        return withExams(rows);
    }

    public Optional<Plan> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscription_plan WHERE id = :id").param("id", id)
                .query((rs, n) -> row(rs, List.of())).optional()
                .map(p -> withExams(List.of(p)).getFirst());
    }

    public boolean codeTaken(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM subscription_plan WHERE code = :c)").param("c", code)
                .query(Boolean.class).single();
    }

    public boolean hasPayments(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment_transaction WHERE plan_id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    public record PlanData(String name, String description, String scopeType, UUID categoryId, List<UUID> examIds,
                           int durationMonths, Long priceLkrMinor, Long priceUsdMinor, Limits limits, boolean active,
                           int displayOrder, Sale sale) {
    }

    public void insert(UUID id, String code, PlanData d) {
        jdbc.sql("""
                INSERT INTO subscription_plan (id, code, name, description, scope_type, category_id, duration_months,
                    price_lkr_minor, price_usd_minor, limit_practice, limit_mock, limit_adaptive, active, display_order,
                    sale_price_lkr_minor, sale_price_usd_minor, sale_starts_at, sale_ends_at)
                VALUES (:id, :code, :name, :description, :scope, :category, :months, :lkr, :usd, :practice, :mock,
                    :adaptive, :active, :order, :saleLkr, :saleUsd, :saleStarts, :saleEnds)
                """)
                .param("id", id).param("code", code).params(params(d))
                .update();
        replaceExams(id, d.examIds());
    }

    /** False when the version doesn't match (someone else saved first). */
    public boolean update(UUID id, int version, PlanData d) {
        boolean saved = jdbc.sql("""
                UPDATE subscription_plan SET name = :name, description = :description, scope_type = :scope,
                    category_id = :category, duration_months = :months, price_lkr_minor = :lkr, price_usd_minor = :usd,
                    limit_practice = :practice, limit_mock = :mock, limit_adaptive = :adaptive, active = :active,
                    display_order = :order, sale_price_lkr_minor = :saleLkr, sale_price_usd_minor = :saleUsd,
                    sale_starts_at = :saleStarts, sale_ends_at = :saleEnds, updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", version).params(params(d))
                .update() == 1;
        if (saved) {
            replaceExams(id, d.examIds());
        }
        return saved;
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM subscription_plan WHERE id = :id").param("id", id).update();
    }

    private void replaceExams(UUID planId, List<UUID> examIds) {
        jdbc.sql("DELETE FROM plan_exam WHERE plan_id = :id").param("id", planId).update();
        for (UUID examId : examIds) {
            jdbc.sql("INSERT INTO plan_exam (plan_id, exam_id) VALUES (:p, :e)").param("p", planId).param("e", examId)
                    .update();
        }
    }

    private static Map<String, Object> params(PlanData d) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("scope", d.scopeType());
        m.put("category", d.categoryId());
        m.put("months", (short) d.durationMonths());
        m.put("lkr", d.priceLkrMinor());
        m.put("usd", d.priceUsdMinor());
        m.put("practice", d.limits().practice());
        m.put("mock", d.limits().mock());
        m.put("adaptive", d.limits().adaptive());
        m.put("active", d.active());
        m.put("order", d.displayOrder());
        Sale sale = d.sale() == null ? new Sale(null, null, null, null) : d.sale();
        m.put("saleLkr", sale.priceLkrMinor());
        m.put("saleUsd", sale.priceUsdMinor());
        m.put("saleStarts", sale.startsAt());
        m.put("saleEnds", sale.endsAt());
        return m;
    }

    private List<Plan> withExams(List<Plan> plans) {
        if (plans.isEmpty()) {
            return plans;
        }
        Map<UUID, List<UUID>> exams = new HashMap<>();
        jdbc.sql("SELECT plan_id, exam_id FROM plan_exam WHERE plan_id IN (:ids) ORDER BY exam_id")
                .param("ids", plans.stream().map(Plan::id).toList())
                .query((rs, n) -> exams.computeIfAbsent(rs.getObject("plan_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getObject("exam_id", UUID.class)))
                .list();
        return plans.stream().map(p -> new Plan(p.id(), p.code(), p.name(), p.description(), p.scopeType(),
                p.categoryId(), List.copyOf(exams.getOrDefault(p.id(), List.of())), p.durationMonths(),
                p.priceLkrMinor(), p.priceUsdMinor(), p.limits(), p.active(), p.displayOrder(), p.version(),
                p.updatedAt(), p.sale())).toList();
    }

    static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Plan row(java.sql.ResultSet rs, List<UUID> examIds) throws java.sql.SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("scope_type"), rs.getObject("category_id", UUID.class),
                examIds, rs.getInt("duration_months"), nullableLong(rs, "price_lkr_minor"),
                nullableLong(rs, "price_usd_minor"),
                new Limits(nullableInt(rs, "limit_practice"), nullableInt(rs, "limit_mock"),
                        nullableInt(rs, "limit_adaptive")),
                rs.getBoolean("active"), rs.getInt("display_order"), rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class),
                new Sale(nullableLong(rs, "sale_price_lkr_minor"), nullableLong(rs, "sale_price_usd_minor"),
                        rs.getObject("sale_starts_at", OffsetDateTime.class),
                        rs.getObject("sale_ends_at", OffsetDateTime.class)));
    }
}
