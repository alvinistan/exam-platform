package com.leenex.exam.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots assessment-service against a real PostgreSQL 16 and checks the Phase 4 foundation. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssessmentServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheMigration() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('attempt') IS NOT NULL", Boolean.class))
                .as("table attempt").isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('attempt_question') IS NOT NULL", Boolean.class))
                .as("table attempt_question").isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('response') IS NOT NULL", Boolean.class))
                .as("table response").isTrue();
    }

    @Test
    void livenessAndReadinessAreUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiEndpointsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/anything").header("X-Correlation-Id", "test-correlation-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation-1"))
                .andExpect(header().string("X-Correlation-Id", "test-correlation-1"));
    }

    @Test
    void internalEndpointsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/internal/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }
}
