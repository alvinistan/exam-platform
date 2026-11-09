package com.leenex.exam.subscription.billing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payment transactions, subscription periods and verified provider notifications (SB-1 … SB-9). */
@Repository
public class Payments {

    public record Transaction(UUID id, UUID studentId, UUID planId, String planName, int durationMonths,
                              String provider, String providerRef, String idempotencyKey, long amountMinor,
                              String currency, String status, String failureReason, OffsetDateTime createdAt,
                              OffsetDateTime updatedAt, String planSnapshot) {

        /** "4500.00", as PayHere sends and expects amounts. */
        public String amount() {
            return java.math.BigDecimal.valueOf(amountMinor, 2).toPlainString();
        }
    }

    public record Period(UUID id, UUID studentId, UUID planId, UUID transactionId, String status,
                         OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime createdAt) {
    }

    private static final String TX = """
            id, student_id, plan_id, plan_name, duration_months, provider, provider_ref, idempotency_key, amount_minor, currency, status,
            failure_reason, created_at, updated_at, plan_snapshot
            """;

    private final JdbcClient jdbc;

    public Payments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- transactions -------------------------------------------------------------------------------------------------

    /** False when this student already used the key (a repeated or simultaneous request). */
    public boolean insertTransaction(UUID id, UUID studentId, Plans.Plan plan, String provider, String key, long amountMinor,
                                     String currency, String planSnapshotJson) {
        return jdbc.sql("""
                INSERT INTO payment_transaction (id, student_id, plan_id, plan_name, duration_months, provider,
                    idempotency_key, amount_minor, currency, plan_snapshot)
                VALUES (:id, :student, :plan, :name, :months, :provider, :key, :amount, :currency,
                    CAST(:snapshot AS jsonb))
                ON CONFLICT (student_id, idempotency_key) DO NOTHING
                """)
                .param("id", id).param("student", studentId).param("plan", plan.id()).param("name", plan.name())
                .param("months", (short) plan.durationMonths()).param("provider", provider)
                .param("key", key).param("amount", amountMinor).param("currency", currency)
                .param("snapshot", planSnapshotJson)
                .update() == 1;
    }

    public Optional<Transaction> transaction(UUID id) {
        return jdbc.sql("SELECT " + TX + " FROM payment_transaction WHERE id = :id").param("id", id)
                .query(Payments::tx).optional();
    }

    public Optional<Transaction> lockTransaction(UUID id) {
        return jdbc.sql("SELECT " + TX + " FROM payment_transaction WHERE id = :id FOR UPDATE").param("id", id)
                .query(Payments::tx).optional();
    }

    public Optional<Transaction> byKey(UUID studentId, String key) {
        return jdbc.sql("SELECT " + TX + " FROM payment_transaction WHERE student_id = :s AND idempotency_key = :k")
                .param("s", studentId).param("k", key).query(Payments::tx).optional();
    }

    public List<Transaction> transactions(UUID studentId, int limit) {
        return jdbc.sql("SELECT " + TX + " FROM payment_transaction WHERE (CAST(:s AS uuid) IS NULL OR student_id = :s)"
                        + " ORDER BY created_at DESC LIMIT :limit")
                .param("s", studentId).param("limit", limit).query(Payments::tx).list();
    }

    public void setStatus(UUID id, String status, String providerRef, String failureReason) {
        jdbc.sql("""
                UPDATE payment_transaction SET status = :status, provider_ref = coalesce(:ref, provider_ref),
                    failure_reason = :reason, updated_at = now(), version = version + 1
                WHERE id = :id
                """)
                .param("id", id).param("status", status).param("ref", providerRef).param("reason", failureReason)
                .update();
    }

    // --- periods ------------------------------------------------------------------------------------------------------

    public void insertPeriod(UUID id, UUID studentId, UUID planId, UUID transactionId, OffsetDateTime startsAt,
                             OffsetDateTime endsAt) {
        jdbc.sql("""
                INSERT INTO subscription (id, student_id, plan_id, transaction_id, status, starts_at, ends_at)
                VALUES (:id, :student, :plan, :tx, 'ACTIVE', :starts, :ends)
                """)
                .param("id", id).param("student", studentId).param("plan", planId).param("tx", transactionId)
                .param("starts", startsAt).param("ends", endsAt)
                .update();
    }

    public Optional<Period> periodFor(UUID transactionId) {
        return jdbc.sql("SELECT * FROM subscription WHERE transaction_id = :tx").param("tx", transactionId)
                .query(Payments::period).optional();
    }

    public List<Period> periods(UUID studentId) {
        return jdbc.sql("SELECT * FROM subscription WHERE student_id = :s ORDER BY starts_at DESC")
                .param("s", studentId).query(Payments::period).list();
    }

    public record PeriodView(UUID id, String planName, String status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    public List<PeriodView> periodViews(UUID studentId) {
        return jdbc.sql("""
                SELECT s.id, t.plan_name, s.status, s.starts_at, s.ends_at
                FROM subscription s JOIN payment_transaction t ON t.id = s.transaction_id
                WHERE s.student_id = :s ORDER BY s.starts_at DESC LIMIT 50
                """).param("s", studentId)
                .query((rs, n) -> new PeriodView(rs.getObject("id", UUID.class), rs.getString("plan_name"),
                        rs.getString("status"), rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class)))
                .list();
    }

    public void setPeriodStatus(UUID id, String status) {
        jdbc.sql("UPDATE subscription SET status = :status WHERE id = :id").param("id", id).param("status", status)
                .update();
    }

    public record Expired(UUID id, UUID studentId) {
    }

    /** Marks periods past their end EXPIRED; returns them. */
    public List<Expired> expirePeriods(OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE subscription SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND ends_at <= :now
                RETURNING id, student_id
                """).param("now", now)
                .query((rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class)))
                .list();
    }

    /** Checkouts nobody finished: PENDING and older than {@code before} become FAILED. */
    public int abandonPending(OffsetDateTime before) {
        return jdbc.sql("""
                UPDATE payment_transaction SET status = 'FAILED', failure_reason = 'NOT_COMPLETED', updated_at = now(),
                    version = version + 1
                WHERE status = 'PENDING' AND created_at < :before
                """).param("before", before).update();
    }

    /** When the student's latest Premium (bought or given, not revoked) ends, if after {@code now}. */
    public Optional<OffsetDateTime> premiumEndsAfter(UUID studentId, OffsetDateTime now) {
        return jdbc.sql("""
                SELECT max(ends_at) FROM entitlement
                WHERE student_id = :s AND revoked_at IS NULL AND ends_at > :now
                """).param("s", studentId).param("now", now)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).list().stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    public boolean premiumActiveAt(UUID studentId, OffsetDateTime at) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM entitlement WHERE student_id = :s AND revoked_at IS NULL
                    AND starts_at <= :at AND ends_at > :at)
                """).param("s", studentId).param("at", at).query(Boolean.class).single();
    }

    /** A bought entitlement, with its own copy of what the plan covered and its limits. */
    public void insertSubscriptionEntitlement(UUID id, UUID studentId, UUID subscriptionId, OffsetDateTime startsAt,
                                              OffsetDateTime endsAt, String planName, String scopeType,
                                              List<UUID> examIds, Plans.Limits limits) {
        jdbc.sql("""
                INSERT INTO entitlement (id, student_id, source, subscription_id, starts_at, ends_at, plan_name,
                    scope_type, limit_practice, limit_mock, limit_adaptive)
                VALUES (:id, :student, 'SUBSCRIPTION', :sub, :starts, :ends, :name, :scope, :practice, :mock, :adaptive)
                """)
                .param("id", id).param("student", studentId).param("sub", subscriptionId).param("starts", startsAt)
                .param("ends", endsAt).param("name", planName).param("scope", scopeType)
                .param("practice", limits.practice()).param("mock", limits.mock()).param("adaptive", limits.adaptive())
                .update();
        for (UUID examId : examIds) {
            jdbc.sql("INSERT INTO entitlement_exam (entitlement_id, exam_id) VALUES (:e, :x)").param("e", id)
                    .param("x", examId).update();
        }
    }

    public void revokeSubscriptionEntitlement(UUID subscriptionId, String reason) {
        jdbc.sql("""
                UPDATE entitlement SET revoked_at = now(), revoked_reason = :reason
                WHERE subscription_id = :sub AND revoked_at IS NULL
                """).param("sub", subscriptionId).param("reason", reason).update();
    }

    // --- provider notifications ---------------------------------------------------------------------------------------

    /** Records a notification once; false when this exact notification was seen before (SB-9). */
    public boolean recordNotification(String provider, String eventId, String type, byte[] sha256) {
        return jdbc.sql("""
                INSERT INTO webhook_event (provider, provider_event_id, event_type, payload_sha256)
                VALUES (:p, :e, :t, :sha) ON CONFLICT DO NOTHING
                """)
                .param("p", provider).param("e", eventId).param("t", type).param("sha", sha256)
                .update() == 1;
    }

    public void notificationOutcome(String provider, String eventId, String outcome) {
        jdbc.sql("""
                UPDATE webhook_event SET processed_at = now(), outcome = :o WHERE provider = :p AND provider_event_id = :e
                """).param("o", outcome).param("p", provider).param("e", eventId).update();
    }

    public void outbox(String type, UUID aggregateId, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:id, 'Subscription', :agg, :type, CAST(:payload AS jsonb))
                """)
                .param("id", com.leenex.exam.platform.web.UuidV7.generate()).param("agg", aggregateId.toString())
                .param("type", type).param("payload", payloadJson)
                .update();
    }

    private static Transaction tx(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Transaction(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getString("plan_name"), rs.getInt("duration_months"),
                rs.getString("provider"), rs.getString("provider_ref"),
                rs.getString("idempotency_key"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("status"), rs.getString("failure_reason"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("plan_snapshot"));
    }

    private static Period period(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Period(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("plan_id", UUID.class), rs.getObject("transaction_id", UUID.class), rs.getString("status"),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
