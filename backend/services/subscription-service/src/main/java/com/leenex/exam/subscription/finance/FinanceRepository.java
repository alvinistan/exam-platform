package com.leenex.exam.subscription.finance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payment search and money reports for administrators (Phase 13). Amounts are per currency and never mixed. */
@Repository
public class FinanceRepository {

    /** A payment as the finance screens list it, with what has been refunded and the plan period it bought. */
    public record PaymentRow(UUID id, UUID studentId, UUID planId, String planName, int durationMonths, String provider,
                             String providerRef, long amountMinor, String currency, String status, String failureReason,
                             Long listPriceMinor, long discountMinor, String discountCode, long refundedMinor,
                             long refundPendingMinor, String periodStatus, OffsetDateTime periodStartsAt,
                             OffsetDateTime periodEndsAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             String refundState) {
    }

    /** NONE, PARTIAL or FULL, counting only refunds that went through. */
    static String refundState(long amountMinor, long refundedMinor) {
        if (refundedMinor <= 0) {
            return "NONE";
        }
        return refundedMinor >= amountMinor ? "FULL" : "PARTIAL";
    }

    /**
     * @param status PAID (succeeded and something was paid), FREE (free with a code), PENDING, FAILED, CANCELLED or
     *               REFUNDED (any refund went through); null = all
     * @param q      a payment id or PayHere payment id
     */
    public record Filter(OffsetDateTime from, OffsetDateTime to, String status, UUID planId, UUID studentId,
                         String currency, String code, String q) {
    }

    private static final String SELECT = """
            WITH r AS (
                SELECT transaction_id,
                       sum(amount_minor) FILTER (WHERE status = 'SUCCEEDED') AS refunded,
                       sum(amount_minor) FILTER (WHERE status = 'PENDING') AS pending
                FROM refund GROUP BY transaction_id)
            SELECT t.id, t.student_id, t.plan_id, t.plan_name, t.duration_months, t.provider, t.provider_ref,
                   t.amount_minor, t.currency, t.status, t.failure_reason, t.list_price_minor, t.discount_minor,
                   d.code AS discount_code, coalesce(r.refunded, 0) AS refunded, coalesce(r.pending, 0) AS pending,
                   s.status AS period_status, s.starts_at AS period_starts_at, s.ends_at AS period_ends_at,
                   t.created_at, t.updated_at
            FROM payment_transaction t
            LEFT JOIN r ON r.transaction_id = t.id
            LEFT JOIN discount_code d ON d.id = t.discount_code_id
            LEFT JOIN subscription s ON s.transaction_id = t.id
            """;

    private static final String WHERE = """
            WHERE (CAST(:from AS timestamptz) IS NULL OR t.created_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR t.created_at < :to)
              AND (CAST(:plan AS uuid) IS NULL OR t.plan_id = :plan)
              AND (CAST(:student AS uuid) IS NULL OR t.student_id = :student)
              AND (CAST(:currency AS text) IS NULL OR t.currency = :currency)
              AND (CAST(:code AS text) IS NULL OR d.code = :code)
              AND (CAST(:q AS text) IS NULL OR t.id::text = :q OR t.provider_ref = :q)
              AND (CAST(:status AS text) IS NULL
                   OR (:status = 'PAID' AND t.status = 'SUCCEEDED' AND t.provider <> 'FREE')
                   OR (:status = 'FREE' AND t.provider = 'FREE')
                   OR (:status = 'REFUNDED' AND coalesce(r.refunded, 0) > 0)
                   OR (:status IN ('PENDING', 'FAILED', 'CANCELLED') AND t.status = :status))
            """;

    private final JdbcClient jdbc;

    public FinanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PaymentRow> search(Filter f, int offset, int limit) {
        return params(jdbc.sql(SELECT + WHERE + " ORDER BY t.created_at DESC, t.id LIMIT :limit OFFSET :offset"), f)
                .param("limit", limit).param("offset", offset)
                .query(FinanceRepository::row).list();
    }

    public long count(Filter f) {
        return params(jdbc.sql("SELECT count(*) FROM (" + SELECT + WHERE + ") x"), f).query(Long.class).single();
    }

    public java.util.Optional<PaymentRow> payment(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(FinanceRepository::row).optional();
    }

    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, Filter f) {
        Map<String, Object> m = new HashMap<>();
        m.put("from", f.from());
        m.put("to", f.to());
        m.put("plan", f.planId());
        m.put("student", f.studentId());
        m.put("currency", f.currency());
        m.put("code", f.code());
        m.put("q", f.q());
        m.put("status", f.status());
        return spec.params(m);
    }

    // --- summary ------------------------------------------------------------------------------------------------------

    /** Money in one currency over the range. Net = paid − refunded (refunds are counted on the day they were made). */
    public record Money(String currency, long paidMinor, long refundedMinor, long netMinor, long discountMinor,
                       int paidPayments, int refunds) {
    }

    public record Counts(int paid, int free, int pending, int failed, int cancelled) {
    }

    public record PlanTotal(String planName, String currency, int payments, long paidMinor) {
    }

    public record Day(LocalDate date, String currency, long paidMinor, long refundedMinor, int payments) {
    }

    public record Subscribers(int activeNow, int endingIn7Days, int endingIn30Days, int newBuyers, int returningBuyers,
                              int freeAccessNow) {
    }

    public List<Money> money(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                WITH paid AS (
                    SELECT currency, sum(amount_minor) AS paid, sum(discount_minor) AS discount, count(*) AS n
                    FROM payment_transaction
                    WHERE status = 'SUCCEEDED' AND provider <> 'FREE' AND created_at >= :from AND created_at < :to
                    GROUP BY currency),
                free AS (
                    SELECT currency, sum(discount_minor) AS discount
                    FROM payment_transaction
                    WHERE status = 'SUCCEEDED' AND provider = 'FREE' AND created_at >= :from AND created_at < :to
                    GROUP BY currency),
                refunded AS (
                    SELECT currency, sum(amount_minor) AS refunded, count(*) AS n
                    FROM refund WHERE status = 'SUCCEEDED' AND created_at >= :from AND created_at < :to
                    GROUP BY currency),
                cur AS (SELECT currency FROM paid UNION SELECT currency FROM refunded UNION SELECT currency FROM free)
                SELECT cur.currency, coalesce(p.paid, 0) AS paid, coalesce(r.refunded, 0) AS refunded,
                       coalesce(p.discount, 0) + coalesce(f.discount, 0) AS discount, coalesce(p.n, 0) AS paid_n,
                       coalesce(r.n, 0) AS refund_n
                FROM cur LEFT JOIN paid p USING (currency) LEFT JOIN refunded r USING (currency)
                LEFT JOIN free f USING (currency)
                ORDER BY cur.currency
                """).param("from", from).param("to", to)
                .query((rs, n) -> {
                    long paid = rs.getLong("paid");
                    long refunded = rs.getLong("refunded");
                    return new Money(rs.getString("currency").trim(), paid, refunded, paid - refunded,
                            rs.getLong("discount"), rs.getInt("paid_n"), rs.getInt("refund_n"));
                })
                .list();
    }

    public Counts counts(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'SUCCEEDED' AND provider <> 'FREE') AS paid,
                       count(*) FILTER (WHERE status = 'SUCCEEDED' AND provider = 'FREE') AS free,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
                FROM payment_transaction WHERE created_at >= :from AND created_at < :to
                """).param("from", from).param("to", to)
                .query((rs, n) -> new Counts(rs.getInt("paid"), rs.getInt("free"), rs.getInt("pending"),
                        rs.getInt("failed"), rs.getInt("cancelled")))
                .single();
    }

    public List<PlanTotal> byPlan(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT plan_name, currency, count(*) AS n, sum(amount_minor) AS paid
                FROM payment_transaction
                WHERE status = 'SUCCEEDED' AND provider <> 'FREE' AND created_at >= :from AND created_at < :to
                GROUP BY plan_name, currency ORDER BY paid DESC, plan_name LIMIT 50
                """).param("from", from).param("to", to)
                .query((rs, n) -> new PlanTotal(rs.getString("plan_name"), rs.getString("currency").trim(),
                        rs.getInt("n"), rs.getLong("paid")))
                .list();
    }

    /** Paid and refunded per day in the administrator's time zone. Days with nothing are left out. */
    public List<Day> days(OffsetDateTime from, OffsetDateTime to, String zone) {
        return jdbc.sql("""
                WITH paid AS (
                    SELECT (created_at AT TIME ZONE :zone)::date AS day, currency, sum(amount_minor) AS paid, count(*) AS n
                    FROM payment_transaction
                    WHERE status = 'SUCCEEDED' AND provider <> 'FREE' AND created_at >= :from AND created_at < :to
                    GROUP BY 1, 2),
                refunded AS (
                    SELECT (created_at AT TIME ZONE :zone)::date AS day, currency, sum(amount_minor) AS refunded
                    FROM refund WHERE status = 'SUCCEEDED' AND created_at >= :from AND created_at < :to
                    GROUP BY 1, 2)
                SELECT coalesce(p.day, r.day) AS day, coalesce(p.currency, r.currency) AS currency,
                       coalesce(p.paid, 0) AS paid, coalesce(r.refunded, 0) AS refunded, coalesce(p.n, 0) AS n
                FROM paid p FULL JOIN refunded r ON r.day = p.day AND r.currency = p.currency
                ORDER BY 1, 2
                """).param("from", from).param("to", to).param("zone", zone)
                .query((rs, n) -> new Day(rs.getObject("day", LocalDate.class), rs.getString("currency").trim(),
                        rs.getLong("paid"), rs.getLong("refunded"), rs.getInt("n")))
                .list();
    }

    /**
     * Students with a bought plan active now, those whose last bought plan ends soon, buyers in the range (new: their
     * first paid payment ever is in the range), and students with free access (campaigns or given) now.
     */
    public Subscribers subscribers(OffsetDateTime now, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                WITH bought AS (
                    SELECT student_id, max(ends_at) AS last_end FROM entitlement
                    WHERE source = 'SUBSCRIPTION' AND revoked_at IS NULL AND starts_at <= :now AND ends_at > :now
                    GROUP BY student_id),
                buyers AS (
                    SELECT student_id, min(created_at) AS first_paid FROM payment_transaction
                    WHERE status = 'SUCCEEDED' AND provider <> 'FREE' GROUP BY student_id),
                in_range AS (
                    SELECT DISTINCT student_id FROM payment_transaction
                    WHERE status = 'SUCCEEDED' AND provider <> 'FREE' AND created_at >= :from AND created_at < :to)
                SELECT (SELECT count(*) FROM bought) AS active,
                       (SELECT count(*) FROM bought WHERE last_end <= :now + interval '7 days') AS end7,
                       (SELECT count(*) FROM bought WHERE last_end <= :now + interval '30 days') AS end30,
                       (SELECT count(*) FROM in_range i JOIN buyers b USING (student_id) WHERE b.first_paid >= :from) AS fresh,
                       (SELECT count(*) FROM in_range i JOIN buyers b USING (student_id) WHERE b.first_paid < :from) AS returning,
                       (SELECT count(DISTINCT student_id) FROM entitlement
                        WHERE source IN ('CAMPAIGN', 'MANUAL') AND revoked_at IS NULL AND starts_at <= :now
                          AND ends_at > :now) AS free_now
                """).param("now", now).param("from", from).param("to", to)
                .query((rs, n) -> new Subscribers(rs.getInt("active"), rs.getInt("end7"), rs.getInt("end30"),
                        rs.getInt("fresh"), rs.getInt("returning"), rs.getInt("free_now")))
                .single();
    }

    /** Students whose last bought plan ends within {@code days}, soonest first (for follow-up). */
    public record Ending(UUID studentId, String planName, OffsetDateTime endsAt) {
    }

    public List<Ending> endingSoon(OffsetDateTime now, int days, int limit) {
        return jdbc.sql("""
                SELECT DISTINCT ON (student_id) student_id, plan_name, ends_at FROM entitlement
                WHERE source = 'SUBSCRIPTION' AND revoked_at IS NULL AND starts_at <= :now AND ends_at > :now
                  AND student_id NOT IN (
                      SELECT student_id FROM entitlement
                      WHERE source = 'SUBSCRIPTION' AND revoked_at IS NULL AND ends_at > :until)
                ORDER BY student_id, ends_at DESC
                """)
                .param("now", now).param("until", now.plusDays(days))
                .query((rs, n) -> new Ending(rs.getObject("student_id", UUID.class), rs.getString("plan_name"),
                        rs.getObject("ends_at", OffsetDateTime.class)))
                .list().stream().sorted(java.util.Comparator.comparing(Ending::endsAt)).limit(limit).toList();
    }

    private static PaymentRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getString("plan_name"), rs.getInt("duration_months"),
                rs.getString("provider"), rs.getString("provider_ref"), rs.getLong("amount_minor"),
                rs.getString("currency").trim(), rs.getString("status"), rs.getString("failure_reason"),
                (Long) rs.getObject("list_price_minor"), rs.getLong("discount_minor"), rs.getString("discount_code"),
                rs.getLong("refunded"), rs.getLong("pending"), rs.getString("period_status"),
                rs.getObject("period_starts_at", OffsetDateTime.class), rs.getObject("period_ends_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                refundState(rs.getLong("amount_minor"), rs.getLong("refunded")));
    }
}
