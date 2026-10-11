package com.leenex.exam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared setup for identity-service integration tests: one PostgreSQL container and one fake Google for the whole
 * run (so the Spring context is reused), plus helpers that drive the real browser sign-in flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class IdentityTestSupport {

    static final String BASE_URL = "http://localhost:8088";
    static final String ADMIN_EMAIL = "first.admin@example.com";
    static final String ADMIN_SUBJECT = "g-first-admin";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final FakeGoogle GOOGLE = new FakeGoogle();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("identity.public-base-url", () -> BASE_URL);
        registry.add("identity.admin-bootstrap-email", () -> ADMIN_EMAIL);
        registry.add("identity.google.client-id", () -> FakeGoogle.CLIENT_ID);
        registry.add("identity.google.client-secret", () -> FakeGoogle.CLIENT_SECRET);
        registry.add("identity.google.authorization-uri", () -> "https://accounts.example.test/auth");
        registry.add("identity.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("identity.google.jwk-set-uri", () -> GOOGLE.baseUrl() + "/certs");
        registry.add("identity.refresh.reuse-grace", () -> "2s");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** Result of a completed browser sign-in. */
    record Session(UUID userId, String refreshToken, String accessToken) {
    }

    /** Parameters Google receives from the start redirect. */
    record Started(String state, String nonce, String codeChallenge, Map<String, List<String>> params) {
    }

    Started start(String returnTo) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/auth/google/start").param("returnTo", returnTo))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        Map<String, List<String>> params = UriComponentsBuilder.fromUri(URI.create(response.getRedirectedUrl()))
                .build(true).getQueryParams();
        String state = decode(params.get("state").getFirst());
        assertThat(setCookie(response, "__Host-oauth")).isEqualTo(state);
        return new Started(state, decode(params.get("nonce").getFirst()),
                decode(params.get("code_challenge").getFirst()), params);
    }

    MockHttpServletResponse callback(Started started, String code, FakeGoogle.Grant grant) throws Exception {
        GOOGLE.willGrant(code, grant);
        return mvc.perform(get("/api/auth/google/callback").param("code", code).param("state", started.state())
                        .cookie(new Cookie("__Host-oauth", started.state())))
                .andReturn().getResponse();
    }

    FakeGoogle.Grant grant(Started started, String subject, String email) {
        return new FakeGoogle.Grant(started.codeChallenge(), subject, email, true, "Test " + subject,
                started.nonce(), FakeGoogle.CLIENT_ID, FakeGoogle.ISSUER);
    }

    /** Signs in through start → Google → callback → token, like the web app does. */
    Session signIn(String subject, String email) throws Exception {
        Started started = start("/");
        MockHttpServletResponse callback = callback(started, "code-" + UUID.randomUUID(), grant(started, subject, email));
        assertThat(callback.getRedirectedUrl()).as("callback redirect").isEqualTo(BASE_URL + "/");
        String refresh = setCookie(callback, "__Host-rt");
        MockHttpServletResponse token = refresh(refresh);
        assertThat(token.getStatus()).as(token.getContentAsString()).isEqualTo(200);
        String access = JsonPath.read(token.getContentAsString(), "$.accessToken");
        String next = setCookie(token, "__Host-rt");
        UUID userId = UUID.fromString(JsonPath.read(mvc.perform(withBearer(get("/api/users/me"), access))
                .andReturn().getResponse().getContentAsString(), "$.id"));
        return new Session(userId, next, access);
    }

    Session signInStudent() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return signIn("g-" + id, "student." + id + "@example.com");
    }

    Session signInAdmin() throws Exception {
        return signIn(ADMIN_SUBJECT, ADMIN_EMAIL);
    }

    MockHttpServletResponse refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/token").header("X-Requested-With", "fetch")
                .cookie(new Cookie("__Host-rt", refreshToken))).andReturn().getResponse();
    }

    static MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    /** Reads one value from a JSON response body. */
    static <T> T read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path);
    }

    /** Value of a cookie set by the response, or null; empty string when the response clears it. */
    static String setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1, h.indexOf(';') < 0 ? h.length() : h.indexOf(';')))
                .reduce((first, last) -> last)
                .orElse(null);
    }

    static String setCookieHeader(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(name + "=")).reduce((a, b) -> b)
                .orElse(null);
    }

    int auditCount(String action, UUID target) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND target_id = ?",
                Integer.class, action, target.toString());
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
