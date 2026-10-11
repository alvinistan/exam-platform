package com.leenex.exam.catalog.question;

/**
 * The two question banks (FR-QB-10, ADR-009). Each is its own schema with the same tables; the schema name comes only
 * from this enum, never from request text, so it is safe to place in SQL.
 */
public enum Bank {
    TRIAL("bank_trial"),
    MAIN("bank_main");

    private final String schema;

    Bank(String schema) {
        this.schema = schema;
    }

    public String schema() {
        return schema;
    }

    /** {@code "q"} → {@code "bank_main.q"}: qualifies a table name with this bank's schema. */
    public String table(String name) {
        return schema + "." + name;
    }
}
