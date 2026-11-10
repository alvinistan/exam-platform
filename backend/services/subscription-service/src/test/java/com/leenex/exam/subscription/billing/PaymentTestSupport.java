package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.leenex.exam.platform.security.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Shared set-up for the payment tests: a database, a JWKS stub, plans and request helpers. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PaymentTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final HttpServer STUB = startStub();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void common(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("platform.security.jwks-uri", () -> base + "/.well-known/jwks.json");
        registry.add("subscription.catalog-feed.enabled", () -> "false");
        registry.add("subscription.service-client.token-uri", () -> base + "/oauth2/token");
        registry.add("subscription.service-client.client-secret", () -> "unused");
        registry.add("subscription.payments.public-base-url", () -> "https://exams.example.lk");
        registry.add("subscription.payments.sweep-initial-delay", () -> "1h");
    }

    static final String ADMIN = TestTokens.admin(UUID.randomUUID());

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    PaymentService service;

    @BeforeEach
    void cleanBilling() {
        jdbc.update("TRUNCATE webhook_event, outbox_event, exam_view, free_attempt_use");
        jdbc.update("TRUNCATE entitlement_use");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM subscription");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM subscription_plan");
    }

    MockHttpServletResponse call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    static MockHttpServletResponse expect(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return r;
    }

    static <T> T read(MockHttpServletResponse r, String path) throws Exception {
        return JsonPath.read(r.getContentAsString(), path);
    }

    static final String DECIDER = TestTokens.builder().subject("assessment-service")
            .roles(com.leenex.exam.platform.security.PlatformJwt.ROLE_SERVICE).scope("billing.access").build();

    /** A plan covering everything, unlimited, priced in one currency, created through the admin API; returns its id. */
    String plan(String code, int months, long priceMinor, String currency, boolean active) throws Exception {
        return plan(planBody(code, months, "LKR".equals(currency) ? priceMinor : null,
                "USD".equals(currency) ? priceMinor : null, "ALL", List.of(), null, null, null, active));
    }

    String plan(Map<String, Object> body) throws Exception {
        return read(expect(call(post("/api/admin/billing/plans"), ADMIN, body), 201), "$.id");
    }

    static Map<String, Object> planBody(String code, int months, Long lkr, Long usd, String scopeType, List<UUID> exams,
                                        Integer practice, Integer mock, Integer adaptive, boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", months + " months");
        body.put("description", "Premium");
        body.put("scopeType", scopeType);
        body.put("examIds", exams.stream().map(UUID::toString).toList());
        body.put("durationMonths", months);
        body.put("priceLkrMinor", lkr);
        body.put("priceUsdMinor", usd);
        body.put("practiceLimit", practice);
        body.put("mockLimit", mock);
        body.put("adaptiveLimit", adaptive);
        body.put("active", active);
        body.put("displayOrder", months);
        return body;
    }

    /** Publishes an exam straight into the read model: all three modes on, Practice 3, Mock 1, Adaptive 3 free. */
    UUID exam(String title, UUID category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO exam_view (exam_id, category_id, title, slug, display_order, status, published_at, "
                + "modes, free_attempts) VALUES (?, ?, ?, ?, 1, 'PUBLISHED', now(), '{PRACTICE,MOCK,USER_FRIENDLY}', "
                + "'{\"PRACTICE\": 3, \"MOCK\": 1, \"USER_FRIENDLY\": 3}')", id, category, title,
                title.toLowerCase().replace(' ', '-'));
        return id;
    }

    /** Asks for an access decision the way assessment-service does, counting the attempt. */
    MockHttpServletResponse start(UUID student, UUID exam, String mode) throws Exception {
        return expect(call(post("/internal/access-decisions"), DECIDER, Map.of("studentId", student.toString(),
                "examId", exam.toString(), "mode", mode, "consume", true, "attemptId", UUID.randomUUID().toString())),
                200);
    }

    static Map<String, Object> customer() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("firstName", "Nimal");
        c.put("lastName", "Perera");
        c.put("email", "nimal@example.lk");
        c.put("phone", "+94 77 123 4567");
        c.put("address", "12 Galle Road");
        c.put("city", "Colombo");
        c.put("country", "Sri Lanka");
        return c;
    }

    MockHttpServletResponse checkout(String token, String planId, String key) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/subscriptions/checkout");
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return call(req, token, Map.of("planId", planId, "customer", customer()));
    }

    boolean premium(String token) throws Exception {
        return read(expect(call(get("/api/access/me"), token, null), 200), "$.premium");
    }

    String txStatus(String token, String txId) throws Exception {
        return read(expect(call(get("/api/subscriptions/checkout/" + txId), token, null), 200), "$.status");
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", ex -> {
                byte[] b = TestTokens.jwksJson().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
