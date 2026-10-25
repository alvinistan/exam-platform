package com.leenex.exam.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leenex.exam.platform.security.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots subscription-service against a real PostgreSQL 16 and checks the Phase 4 foundation. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SubscriptionServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    /** Stands in for identity-service's JWKS endpoint, publishing the test signing key. */
    static final HttpServer JWKS = startJwks();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("platform.security.jwks-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheMigration() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('free_attempt_use') IS NOT NULL", Boolean.class))
                .as("table free_attempt_use").isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('free_access_policy') IS NULL", Boolean.class))
                .as("retired table free_access_policy").isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('subscription_plan') IS NOT NULL", Boolean.class))
                .as("table subscription_plan").isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('entitlement') IS NOT NULL", Boolean.class))
                .as("table entitlement").isTrue();
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
    void validAccessTokenIsAcceptedUsingIdentityKeys() throws Exception {
        // Past security (keys fetched from the JWKS endpoint), the unknown path is a plain 404.
        mvc.perform(get("/api/anything").header("Authorization", "Bearer " + TestTokens.student(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        String token = TestTokens.student(UUID.randomUUID());
        mvc.perform(get("/api/anything").header("Authorization", "Bearer " + token.substring(0, token.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentsCannotReachAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + TestTokens.student(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void internalEndpointsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/internal/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/events").header("Authorization", "Bearer " + TestTokens.admin(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = TestTokens.jwksJson().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
