package com.leenex.exam.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    }

    @AfterAll
    static void stopUpstreams() {
        UPSTREAMS.values().forEach(server -> server.stop(0));
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
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
        "/api/attempts/estimate,              assessment",
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
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static HttpServer startUpstream(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(name + " " + path + " " + exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
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
