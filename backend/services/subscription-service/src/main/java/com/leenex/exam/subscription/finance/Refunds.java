package com.leenex.exam.subscription.finance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Refunds of payments (Phase 13). A refund counts against its payment while PENDING or SUCCEEDED. */
@Repository
public class Refunds {

    public record Refund(UUID id, UUID transactionId, long amountMinor, String currency, String reason, String method,
                         String status, String providerRef, String failureReason, boolean stopAccess, UUID createdBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private static final String COLUMNS = """
            id, transaction_id, amount_minor, currency, reason, method, status, provider_ref, failure_reason,
            stop_access, created_by, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public Refunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID transactionId, long amountMinor, String currency, String reason, String method,
                       String status, String providerRef, boolean stopAccess, UUID createdBy) {
        jdbc.sql("""
                INSERT INTO refund (id, transaction_id, amount_minor, currency, reason, method, status, provider_ref,
                    stop_access, created_by)
                VALUES (:id, :tx, :amount, :currency, :reason, :method, :status, :ref, :stop, :by)
                """)
                .param("id", id).param("tx", transactionId).param("amount", amountMinor).param("currency", currency)
                .param("reason", reason).param("method", method).param("status", status).param("ref", providerRef)
                .param("stop", stopAccess).param("by", createdBy)
                .update();
    }

    /** Settles a PENDING refund; false when it was already settled. */
    public boolean settle(UUID id, String status, String providerRef, String failureReason) {
        return jdbc.sql("""
                UPDATE refund SET status = :status, provider_ref = coalesce(:ref, provider_ref),
                    failure_reason = :reason, updated_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id).param("status", status).param("ref", providerRef).param("reason", failureReason)
                .update() == 1;
    }

    public Optional<Refund> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE id = :id").param("id", id).query(Refunds::row)
                .optional();
    }

    public Optional<Refund> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE id = :id FOR UPDATE").param("id", id)
                .query(Refunds::row).optional();
    }

    public List<Refund> forTransaction(UUID transactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE transaction_id = :tx ORDER BY created_at")
                .param("tx", transactionId).query(Refunds::row).list();
    }

    /** Pending and done refunds of a payment: what can't be refunded again. */
    public long committed(UUID transactionId) {
        return jdbc.sql("""
                SELECT coalesce(sum(amount_minor), 0) FROM refund
                WHERE transaction_id = :tx AND status IN ('PENDING', 'SUCCEEDED')
                """).param("tx", transactionId).query(Long.class).single();
    }

    private static Refund row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Refund(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("reason"),
                rs.getString("method"), rs.getString("status"), rs.getString("provider_ref"),
                rs.getString("failure_reason"), rs.getBoolean("stop_access"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
