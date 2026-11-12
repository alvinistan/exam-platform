package com.leenex.exam.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.leenex.exam.platform.security.PlatformJwt;
import com.leenex.exam.platform.security.TestTokens;
import com.leenex.exam.subscription.catalogfeed.CatalogFeedPoller;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** The catalogue read model, free attempts per exam type, entitlements and access decisions, end to end. */
@SpringBootTest
@AutoConfigureMockMvc
class AccessTests {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final String CLIENT_SECRET = "subscription-test-secret";
    /** Stands in for identity-service (JWKS, client-credentials token) and catalog-service (event feed). */
    static final HttpServer STUB = startStub();
    static final List<Map<String, Object>> FEED = new CopyOnWriteArrayList<>();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("platform.security.jwks-uri", () -> base + "/.well-known/jwks.json");
        registry.add("subscription.catalog-feed.enabled", () -> "false");
        registry.add("subscription.catalog-feed.base-url", () -> base);
        registry.add("subscription.service-client.token-uri", () -> base + "/oauth2/token");
        registry.add("subscription.service-client.client-secret", () -> CLIENT_SECRET);
    }

    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String ADMIN = TestTokens.admin(ADMIN_ID);
    static final String DECIDER = TestTokens.builder().subject("assessment-service").roles(PlatformJwt.ROLE_SERVICE)
            .scope("billing.access").build();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    CatalogFeedPoller poller;

    final UUID category = UUID.randomUUID();

    @BeforeEach
    void cleanState() {
        jdbc.update("TRUNCATE exam_view, free_attempt_use, processed_event, feed_cursor");
        jdbc.update("TRUNCATE entitlement_use");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("TRUNCATE campaign_exam");
        jdbc.update("DELETE FROM campaign");
        FEED.clear();
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /**
     * Publishes {@code count} exams straight into the read model, all three modes on, with Practice 3, Mock 1 and
     * Adaptive 3 free attempts; returns their IDs in order.
     */
    List<UUID> published(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO exam_view (exam_id, category_id, title, slug, display_order, status, published_at, "
                    + "modes, free_attempts) VALUES (?, ?, ?, ?, ?, 'PUBLISHED', now(), '{PRACTICE,MOCK,USER_FRIENDLY}', "
                    + "'{\"PRACTICE\": 3, \"MOCK\": 1, \"USER_FRIENDLY\": 3}')", id, category, "Exam " + i, "exam-" + i, i);
            ids.add(id);
        }
        return ids;
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

    MockHttpServletResponse access(String token, List<UUID> ids) throws Exception {
        return expect(call(get("/api/access/exams").param("examIds",
                String.join(",", ids.stream().map(UUID::toString).toList())), token, null), 200);
    }

    MockHttpServletResponse decide(UUID student, UUID exam, String mode, boolean consume, UUID attemptId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("studentId", student.toString());
        body.put("examId", exam.toString());
        body.put("mode", mode);
        body.put("consume", consume);
        if (attemptId != null) {
            body.put("attemptId", attemptId.toString());
        }
        return expect(call(post("/internal/access-decisions"), DECIDER, body), 200);
    }

    MockHttpServletResponse grant(UUID student, OffsetDateTime endsAt) throws Exception {
        return call(post("/api/admin/billing/entitlements"), ADMIN,
                Map.of("studentId", student.toString(), "endsAt", endsAt.toString(), "reason", "Complimentary access"));
    }

    // --- catalogue feed -----------------------------------------------------------------------------------------------

    @Test
    void theCatalogueFeedBuildsTheReadModel() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FEED.add(event(1, "ExamPublished", first, 1, "PUBLISHED"));
        FEED.add(event(2, "ExamPublished", second, 2, "PUBLISHED"));
        FEED.add(event(3, "SomethingElse", second, 2, "PUBLISHED"));
        FEED.add(event(4, "ExamArchived", second, 2, "ARCHIVED"));
        assertThat(poller.pollOnce()).isEqualTo(4);
        assertThat(poller.pollOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_seq FROM feed_cursor WHERE source = 'catalog'", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM exam_view WHERE exam_id = ?", String.class, second)).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT title FROM exam_view WHERE exam_id = ?", String.class, first)).isEqualTo("Exam " + first);

        // A re-delivered event (cursor reset) is recognised and not applied twice.
        jdbc.update("UPDATE feed_cursor SET last_seq = 0");
        FEED.add(event(5, "ExamRestored", second, 2, "DRAFT"));
        poller.pollOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM exam_view WHERE exam_id = ?", String.class, second)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isEqualTo(5);
    }

    @Test
    void theFeedCarriesFreeAttemptsPerExamType() throws Exception {
        UUID exam = UUID.randomUUID();
        Map<String, Object> e = event(1, "ExamPublished", exam, 1, "PUBLISHED");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.get("payload");
        payload.put("modes", List.of("PRACTICE", "MOCK"));
        payload.put("freeAttempts", Map.of("PRACTICE", 2, "MOCK", 1, "NONSENSE", 4));
        FEED.add(e);
        poller.pollOnce();
        MockHttpServletResponse r = access(null, List.of(exam));
        assertThat((List<String>) read(r, "$['" + exam + "'].modes[*].mode")).containsExactly("PRACTICE", "MOCK");
        assertThat((List<Integer>) read(r, "$['" + exam + "'].modes[*].freeAttempts")).containsExactly(2, 1);
        MockHttpServletResponse list = expect(call(get("/api/admin/billing/free-attempts"), ADMIN, null), 200);
        assertThat((Integer) read(list, "$[0].freeAttempts.PRACTICE")).isEqualTo(2);
        expect(call(get("/api/admin/billing/free-attempts"), TestTokens.student(UUID.randomUUID()), null), 403);
    }

    @Test
    void anonymousVisitorsSeeFreeAttemptsAndUnknownExamsAreUnavailable() throws Exception {
        List<UUID> exams = published(1);
        UUID unknown = UUID.randomUUID();
        MockHttpServletResponse r = access(null, List.of(exams.get(0), unknown));
        assertThat((Boolean) read(r, "$['" + exams.get(0) + "'].available")).isTrue();
        assertThat((List<String>) read(r, "$['" + exams.get(0) + "'].modes[*].access")).containsExactly("FREE", "FREE", "FREE");
        assertThat((Boolean) read(r, "$['" + unknown + "'].available")).isFalse();

        expect(call(get("/api/access/exams"), null, null), 400);
        expect(call(get("/api/access/me"), null, null), 401);
    }

    @Test
    void freeAttemptsAreCountedPerExamTypeAndLockWhenUsedUp() throws Exception {
        List<UUID> exams = published(2);
        UUID student = UUID.randomUUID();
        String token = TestTokens.student(student);
        UUID exam = exams.get(0);

        UUID first = UUID.randomUUID();
        MockHttpServletResponse ok = decide(student, exam, "MOCK", true, first);
        assertThat((String) read(ok, "$.reason")).isEqualTo("FREE_ATTEMPT");
        assertThat((Integer) read(ok, "$.attemptsLeft")).isZero();
        // The same attempt asked again (a retry) is still allowed and not counted twice.
        assertThat((Boolean) read(decide(student, exam, "MOCK", true, first), "$.allowed")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM free_attempt_use WHERE student_id = ?", Integer.class, student)).isEqualTo(1);
        // A new Mock attempt is refused; Practice on the same exam and Mock on another exam are counted separately.
        assertThat((String) read(decide(student, exam, "MOCK", true, UUID.randomUUID()), "$.reason")).isEqualTo("FREE_ATTEMPTS_USED");
        assertThat((Boolean) read(decide(student, exam, "PRACTICE", true, UUID.randomUUID()), "$.allowed")).isTrue();
        assertThat((Boolean) read(decide(student, exams.get(1), "MOCK", false, null), "$.allowed")).isTrue();

        MockHttpServletResponse mine = access(token, List.of(exam));
        assertThat((String) read(mine, "$['" + exam + "'].modes[1].access")).isEqualTo("LOCKED");
        assertThat((Integer) read(mine, "$['" + exam + "'].modes[0].left")).isEqualTo(2);
        assertThat((Integer) read(mine, "$['" + exam + "'].modes[0].used")).isEqualTo(1);
    }

    @Test
    void parallelStartsCannotOverspendFreeAttempts() throws Exception {
        UUID exam = published(1).get(0);
        UUID student = UUID.randomUUID();
        List<Thread> threads = new ArrayList<>();
        List<Boolean> allowed = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    allowed.add(read(decide(student, exam, "PRACTICE", true, UUID.randomUUID()), "$.allowed"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(allowed.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
    }

    // --- entitlements (FA-7) -----------------------------------------------------------------------------------------

    @Test
    void anEntitlementUnlocksPremiumExamsUntilItIsRevoked() throws Exception {
        List<UUID> exams = published(7);
        UUID student = UUID.randomUUID();
        String token = TestTokens.student(student);
        UUID premium = exams.get(6);
        decide(student, premium, "MOCK", true, UUID.randomUUID());
        assertThat((String) read(access(token, List.of(premium)), "$['" + premium + "'].modes[1].access")).isEqualTo("LOCKED");
        assertThat((Boolean) read(expect(call(get("/api/access/me"), token, null), 200), "$.premium")).isFalse();

        MockHttpServletResponse granted = expect(grant(student, OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)), 201);
        String id = read(granted, "$.id");
        assertThat((String) read(granted, "$.state")).isEqualTo("ACTIVE");
        assertThat((String) read(access(token, List.of(premium)), "$['" + premium + "'].modes[1].access")).isEqualTo("OPEN");
        MockHttpServletResponse me = expect(call(get("/api/access/me"), token, null), 200);
        assertThat((Boolean) read(me, "$.premium")).isTrue();
        assertThat((String) read(me, "$.entitlement.source")).isEqualTo("MANUAL");
        assertThat((String) read(decide(student, premium, "MOCK", false, null), "$.bank")).isEqualTo("MAIN");

        // Another student is unaffected.
        assertThat((Boolean) read(access(TestTokens.student(UUID.randomUUID()), List.of(premium)), "$['" + premium + "'].premium"))
                .isFalse();

        assertThat((List<String>) read(expect(call(get("/api/admin/billing/entitlements").param("studentId", student.toString()),
                ADMIN, null), 200), "$[*].grantReason")).containsExactly("Complimentary access");
        expect(call(post("/api/admin/billing/entitlements/" + id + "/revoke"), ADMIN, Map.of("reason", "Refunded")), 204);
        assertThat((String) read(access(token, List.of(premium)), "$['" + premium + "'].modes[1].access")).isEqualTo("LOCKED");
        expect(call(post("/api/admin/billing/entitlements/" + id + "/revoke"), ADMIN, Map.of("reason", "Again")), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action IN ('ENTITLEMENT_GRANTED', 'ENTITLEMENT_REVOKED') AND target_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void expiredEntitlementsDoNotGrantAccessAndGrantsAreValidated() throws Exception {
        List<UUID> exams = published(7);
        UUID student = UUID.randomUUID();
        jdbc.update("INSERT INTO entitlement (id, student_id, source, starts_at, ends_at, grant_reason, granted_by) "
                + "VALUES (?, ?, 'MANUAL', now() - interval '10 days', now() - interval '1 second', 'old', ?)",
                UUID.randomUUID(), student, ADMIN_ID);
        assertThat((Boolean) read(access(TestTokens.student(student), List.of(exams.get(6))), "$['" + exams.get(6) + "'].premium"))
                .isFalse();
        assertThat((String) read(expect(call(get("/api/admin/billing/entitlements").param("studentId", student.toString()),
                ADMIN, null), 200), "$[0].state")).isEqualTo("EXPIRED");

        expect(grant(student, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)), 400);
        expect(grant(student, OffsetDateTime.now(ZoneOffset.UTC).plusYears(6)), 400);
        expect(call(post("/api/admin/billing/entitlements"), TestTokens.student(student),
                Map.of("studentId", student.toString(), "endsAt", OffsetDateTime.now().plusDays(1).toString(), "reason", "Self")), 403);
    }

    // --- decisions (ADR-007) ------------------------------------------------------------------------------------------

    @Test
    void decisionsFollowTheRulesAndOnlyServicesWithTheScopeMayAsk() throws Exception {
        List<UUID> exams = published(1);
        UUID student = UUID.randomUUID();
        MockHttpServletResponse free = decide(student, exams.get(0), "PRACTICE", false, null);
        assertThat((Boolean) read(free, "$.allowed")).isTrue();
        assertThat((String) read(free, "$.bank")).isEqualTo("MAIN");
        assertThat((String) read(decide(student, UUID.randomUUID(), "PRACTICE", false, null), "$.reason")).isEqualTo("EXAM_UNAVAILABLE");
        jdbc.update("UPDATE exam_view SET modes = '{PRACTICE}'");
        assertThat((String) read(decide(student, exams.get(0), "MOCK", false, null), "$.reason")).isEqualTo("MODE_NOT_AVAILABLE");

        Map<String, Object> body = Map.of("studentId", student.toString(), "examId", exams.get(0).toString(), "mode", "PRACTICE");
        expect(call(post("/internal/access-decisions"), ADMIN, body), 403);
        String noScope = TestTokens.builder().subject("learning-service").roles(PlatformJwt.ROLE_SERVICE).scope("catalog.internal").build();
        expect(call(post("/internal/access-decisions"), noScope, body), 403);
        expect(call(post("/internal/access-decisions"), DECIDER,
                Map.of("studentId", student.toString(), "examId", exams.get(0).toString(), "mode", "EVERYTHING")), 400);
        expect(call(post("/internal/access-decisions"), DECIDER,
                Map.of("studentId", student.toString(), "examId", exams.get(0).toString(), "mode", "PRACTICE", "consume", true)), 400);
    }

    // --- stub --------------------------------------------------------------------------------------------------------

    Map<String, Object> event(long seq, String type, UUID exam, int order, String status) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("examId", exam.toString());
        payload.put("slug", "exam-" + seq);
        payload.put("title", "Exam " + exam);
        payload.put("categoryId", category.toString());
        payload.put("displayOrder", order);
        payload.put("status", status);
        return Map.of("seq", seq, "id", UUID.nameUUIDFromBytes(("event-" + seq).getBytes(StandardCharsets.UTF_8)).toString(),
                "type", type, "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(), "payload", payload);
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JsonMapper mapper = JsonMapper.builder().build();
            server.createContext("/.well-known/jwks.json", ex -> send(ex, 200, TestTokens.jwksJson()));
            server.createContext("/oauth2/token", ex -> {
                String expected = "Basic " + Base64.getEncoder().encodeToString(
                        ("subscription-service:" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    send(ex, 401, "{\"error\":\"invalid_client\"}");
                    return;
                }
                String token = TestTokens.builder().subject("subscription-service").roles(PlatformJwt.ROLE_SERVICE)
                        .scope("catalog.internal").build();
                send(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
            });
            server.createContext("/internal/events", ex -> {
                if (!String.valueOf(ex.getRequestHeaders().getFirst("Authorization")).startsWith("Bearer ")) {
                    send(ex, 401, "{}");
                    return;
                }
                long after = Long.parseLong(ex.getRequestURI().getQuery().replaceAll(".*after=(\\d+).*", "$1"));
                List<Map<String, Object>> page = FEED.stream().filter(e -> ((Number) e.get("seq")).longValue() > after).toList();
                send(ex, 200, mapper.writeValueAsString(page));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
