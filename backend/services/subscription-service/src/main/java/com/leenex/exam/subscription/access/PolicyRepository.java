package com.leenex.exam.subscription.access;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The free_access_policy rows. */
@Repository
public class PolicyRepository {

    public record PolicyRow(String ruleCode, boolean active, JsonNode params, int version, OffsetDateTime updatedAt,
                            UUID updatedBy) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public PolicyRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PolicyRow> all() {
        return jdbc.sql("""
                SELECT rule_code, active, params::text AS params, version, updated_at, updated_by
                FROM free_access_policy ORDER BY rule_code
                """)
                .query((rs, n) -> new PolicyRow(rs.getString("rule_code"), rs.getBoolean("active"),
                        json.readTree(rs.getString("params")), rs.getInt("version"),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", UUID.class)))
                .list();
    }

    public Optional<PolicyRow> lock(String ruleCode) {
        return jdbc.sql("""
                SELECT rule_code, active, params::text AS params, version, updated_at, updated_by
                FROM free_access_policy WHERE rule_code = :code FOR UPDATE
                """)
                .param("code", ruleCode)
                .query((rs, n) -> new PolicyRow(rs.getString("rule_code"), rs.getBoolean("active"),
                        json.readTree(rs.getString("params")), rs.getInt("version"),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", UUID.class)))
                .optional();
    }

    public void update(String ruleCode, boolean active, JsonNode params, UUID actor) {
        jdbc.sql("""
                UPDATE free_access_policy
                SET active = :active, params = CAST(:params AS jsonb), updated_by = :actor, updated_at = now(),
                    version = version + 1
                WHERE rule_code = :code
                """)
                .param("code", ruleCode).param("active", active).param("params", json.writeValueAsString(params))
                .param("actor", actor)
                .update();
    }

    /** The rules in force. Missing or malformed values fall back to the most restrictive reading. */
    public FreePolicy current() {
        boolean positionActive = false;
        int count = 0;
        String scope = "GLOBAL";
        boolean welcomeActive = false;
        int attempts = 0;
        boolean featuresActive = false;
        List<String> modes = new ArrayList<>();
        for (PolicyRow row : all()) {
            JsonNode p = row.params();
            switch (row.ruleCode()) {
                case "FA1_CATALOGUE_POSITION" -> {
                    positionActive = row.active();
                    count = p.path("count").asInt(0);
                    scope = p.path("scope").asString("GLOBAL");
                }
                case "FA2_WELCOME_ALLOWANCE" -> {
                    welcomeActive = row.active();
                    attempts = p.path("attempts").asInt(0);
                }
                case "FA4_FREE_FEATURES" -> {
                    featuresActive = row.active();
                    p.path("modes").forEach(m -> modes.add(m.asString()));
                }
                default -> {
                    // unknown rules are ignored
                }
            }
        }
        return new FreePolicy(positionActive, count, scope, welcomeActive, attempts, featuresActive, List.copyOf(modes));
    }
}
