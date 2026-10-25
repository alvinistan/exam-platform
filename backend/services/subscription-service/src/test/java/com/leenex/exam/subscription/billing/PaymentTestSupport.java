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
        jdbc.update("TRUNCATE webhook_event, outbox_event");
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

    /** Creates a plan through the admin API and returns its id. */
    String plan(String code, int months, long priceMinor, String currency, boolean active) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", months + " months");
        body.put("description", "All Premium exams");
        body.put("durationMonths", months);
        body.put("priceMinor", priceMinor);
        body.put("currency", currency);
        body.put("active", active);
        body.put("displayOrder", months);
        return read(expect(call(post("/api/admin/billing/plans"), ADMIN, body), 201), "$.id");
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
