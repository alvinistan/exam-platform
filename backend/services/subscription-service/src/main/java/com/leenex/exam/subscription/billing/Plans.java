package com.leenex.exam.subscription.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Subscription plans (FR-SUB-01). Prices are set by administrators, in the currency's smallest unit (SB-7). */
@Repository
public class Plans {

    public record Plan(UUID id, String code, String name, String description, int durationMonths, long priceMinor,
                       String currency, boolean active, int displayOrder, int version, OffsetDateTime updatedAt) {

        /** "4500.00": the amount PayHere expects (two decimals, a dot, no thousands separator). */
        public String amount() {
            return BigDecimal.valueOf(priceMinor, 2).toPlainString();
        }
    }

    private static final String COLUMNS = """
            id, code, name, description, duration_months, price_minor, currency, active, display_order, version, updated_at
            """;

    private final JdbcClient jdbc;

    public Plans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> all(boolean activeOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscription_plan" + (activeOnly ? " WHERE active" : "")
                        + " ORDER BY display_order, duration_months, code")
                .query(Plans::row).list();
    }

    public Optional<Plan> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM subscription_plan WHERE id = :id").param("id", id)
                .query(Plans::row).optional();
    }

    public boolean codeTaken(String code, UUID exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM subscription_plan WHERE code = :c AND id IS DISTINCT FROM :id)")
                .param("c", code).param("id", exceptId).query(Boolean.class).single();
    }

    public void insert(UUID id, String code, String name, String description, int months, long priceMinor,
                       String currency, boolean active, int displayOrder) {
        jdbc.sql("""
                INSERT INTO subscription_plan (id, code, name, description, duration_months, price_minor, currency,
                    active, display_order)
                VALUES (:id, :code, :name, :description, :months, :price, :currency, :active, :order)
                """)
                .param("id", id).param("code", code).param("name", name).param("description", description)
                .param("months", (short) months).param("price", priceMinor).param("currency", currency)
                .param("active", active).param("order", displayOrder)
                .update();
    }

    /** False when the version doesn't match (someone else saved first). */
    public boolean update(UUID id, int version, String name, String description, int months, long priceMinor,
                          String currency, boolean active, int displayOrder) {
        return jdbc.sql("""
                UPDATE subscription_plan SET name = :name, description = :description, duration_months = :months,
                    price_minor = :price, currency = :currency, active = :active, display_order = :order,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", version).param("name", name).param("description", description)
                .param("months", (short) months).param("price", priceMinor).param("currency", currency)
                .param("active", active).param("order", displayOrder)
                .update() == 1;
    }

    private static Plan row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getInt("duration_months"), rs.getLong("price_minor"),
                rs.getString("currency"), rs.getBoolean("active"), rs.getInt("display_order"), rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
