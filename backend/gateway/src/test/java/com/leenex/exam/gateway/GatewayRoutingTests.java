package com.leenex.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leenex.exam.platform.security.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts one tiny HTTP server per service and checks that each public path prefix reaches the right one, that
 * correlation IDs are forwarded, and that {@code /internal/**} is never routed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingTests {

    private static final List<String> SERVICES = List.of("identity", "catalog", "assessment", "learning", "subscription");
    private static final Map<String, HttpServer> UPSTREAMS = new ConcurrentHashMap<>();
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    private static final HttpServer JWKS = startJwks();

    static {
        for (String service : SERVICES) {
            UPSTREAMS.put(service, startUpstream(service));
        }
    }

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        for (String service : SERVICES) {
            int port = UPSTREAMS.get(service).getAddress().getPort();
            registry.add(service.toUpperCase() + "_SERVICE_URL", () -> "http://127.0.0.1:" + port);
        }
        registry.add("gateway.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopUpstreams() {
        UPSTREAMS.values().forEach(server -> server.stop(0));
        JWKS.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
        BODIES.clear();
    }

    @Test
    void uploadsReachTheServiceUntouched() throws Exception {
        byte[] body = new byte[3 * 1024 * 1024 + 17];
        java.util.Arrays.fill(body, (byte) 'x');
        mvc.perform(post("/api/admin/catalog/media")
                        .header("Authorization", "Bearer " + TestTokens.admin(UUID.randomUUID()))
                        .contentType("multipart/form-data; boundary=leenex-boundary").content(body))
                .andExpect(status().isOk());
        // MockMvc adds a charset to the content type; the boundary and every byte must arrive unchanged.
        assertThat(BODIES).singleElement().asString()
                .startsWith("multipart/form-data; boundary=leenex-boundary").endsWith(" " + body.length);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "/api/auth/token,                     identity",
        "/api/users/me,                       identity",
        "/api/admin/users,                    identity",
        "/.well-known/jwks.json,              identity",
        "/api/categories,                     catalog",
        "/api/exams/by-slug/ielts-academic,   catalog",
        "/api/admin/catalog/exams,            catalog",
        "/api/media/0123456789abcdef0123456789abcdef.png, catalog",
        "/api/attempts/estimate,              assessment",
        "/api/progress/exams,                 assessment",
        "/api/admin/attempts,                 assessment",
        "/api/learning/topics,                learning",
        "/api/admin/learning/analytics,       learning",
        "/api/access/exams,                   subscription",
        "/api/plans,                          subscription",
        "/api/subscriptions/me,               subscription",
        "/api/payments/webhooks/sandbox,      subscription",
        "/api/admin/billing/policies,         subscription"
    })
    void routesEachPathToItsOwningService(String path, String service, CapturedOutput output) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus())
                .as("status for %s; body: %s; upstream saw: %s; log tail: %s", path, response.getContentAsString(), RECEIVED,
                        tail(output.getAll(), 4000))
                .isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(service + " " + path);
    }

    @Test
    void forwardsTheCallersCorrelationId() throws Exception {
        mvc.perform(get("/api/plans").header("X-Correlation-Id", "corr-abcdef12"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-abcdef12"));
        assertThat(RECEIVED).containsExactly("subscription /api/plans corr-abcdef12");
    }

    @Test
    void createsACorrelationIdWhenMissing() throws Exception {
        String id = mvc.perform(get("/api/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(id).hasSize(36);
        assertThat(RECEIVED).containsExactly("subscription /api/plans " + id);
    }

    @ParameterizedTest
    @CsvSource({"/internal/events", "/internal/access-decisions", "/api/internal/events", "/api/unknown"})
    void unlistedAndInternalPathsAreNotRouted(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void validTokensPassThroughToTheService() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + TestTokens.student(UUID.randomUUID())))
                .andExpect(status().isOk());
        assertThat(RECEIVED).singleElement().asString().startsWith("identity /api/users/me ");
    }

    @Test
    void forgedOrExpiredTokensStopAtTheGateway() throws Exception {
        String token = TestTokens.student(UUID.randomUUID());
        String expired = TestTokens.builder().issuedAt(java.time.Instant.now().minusSeconds(3600)).build();
        for (String bad : List.of(token.substring(0, token.length() - 3) + "xyz", expired, "not-a-jwt")) {
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void signInIsRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/api/auth/google/start").with(from("10.0.0.21"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/auth/google/start").with(from("10.0.0.21")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // Another address has its own allowance.
        mvc.perform(get("/api/auth/google/start").with(from("10.0.0.22"))).andExpect(status().isOk());
    }

    /** A class on one connection opens the app together: token refreshes have their own, larger allowance. */
    @Test
    void tokenRefreshHasItsOwnLargerAllowance() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/api/auth/google/start").with(from("10.0.0.41"))).andExpect(status().isOk());
        }
        for (int i = 0; i < 300; i++) {
            mvc.perform(post("/api/auth/token").with(from("10.0.0.41"))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/token").with(from("10.0.0.41")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void checkoutIsRateLimitedPerUserNotPerAddress() throws Exception {
        String alice = TestTokens.student(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/subscriptions/checkout").header("Authorization", "Bearer " + alice).with(from("10.0.0.31")))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", "Bearer " + alice).with(from("10.0.0.32")))
                .andExpect(status().isTooManyRequests());
        String bob = TestTokens.student(UUID.randomUUID());
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", "Bearer " + bob).with(from("10.0.0.31")))
                .andExpect(status().isOk());
        // Reading plans is not limited.
        mvc.perform(get("/api/subscriptions/me").header("Authorization", "Bearer " + alice)).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = TestTokens.jwksJson().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startUpstream(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(name + " " + path + " " + exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
                byte[] received = exchange.getRequestBody().readAllBytes();
                if (received.length > 0) {
                    BODIES.add(exchange.getRequestHeaders().getFirst("Content-Type") + " " + received.length);
                }
                byte[] body = (name + " " + path).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
