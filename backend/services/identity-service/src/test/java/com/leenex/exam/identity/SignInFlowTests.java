package com.leenex.exam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** The Google sign-in flow and token lifecycle, end to end against PostgreSQL and a fake Google. */
class SignInFlowTests extends IdentityTestSupport {

    @Test
    void startRedirectsToGoogleWithPkceNonceAndABrowserBoundState() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/auth/google/start").param("returnTo", "/exams/x"))
                .andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("https://accounts.example.test/auth?");
        Started started = start("/exams/x");
        assertThat(started.params().get("client_id")).containsExactly(FakeGoogle.CLIENT_ID);
        assertThat(started.params().get("response_type")).containsExactly("code");
        assertThat(started.params().get("code_challenge_method")).containsExactly("S256");
        assertThat(java.net.URLDecoder.decode(started.params().get("redirect_uri").getFirst(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("http://localhost:8088/api/auth/google/callback");
        assertThat(started.codeChallenge()).hasSize(43);
        assertThat(started.nonce()).isNotBlank();
        assertThat(setCookieHeader(response, "__Host-oauth"))
                .contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=600");
    }

    @Test
    void firstSignInCreatesAStudentAndReturnsToThePageTheyCameFrom() throws Exception {
        Started started = start("/exams/ielts-academic?mode=practice");
        MockHttpServletResponse callback = callback(started, "c-first", grant(started, "g-new-student", "New.Student@Example.com"));

        assertThat(callback.getStatus()).isEqualTo(302);
        assertThat(callback.getRedirectedUrl()).isEqualTo(BASE_URL + "/exams/ielts-academic?mode=practice");
        assertThat(setCookieHeader(callback, "__Host-rt"))
                .contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(setCookie(callback, "__Host-oauth")).isEmpty(); // single-use: cleared

        MockHttpServletResponse token = refresh(setCookie(callback, "__Host-rt"));
        assertThat(token.getStatus()).isEqualTo(200);
        assertThat((String) read(token, "$.tokenType")).isEqualTo("Bearer");
        assertThat((Integer) read(token, "$.expiresIn")).isEqualTo(900);
        assertThat(token.getContentAsString()).as("web never sees the refresh token").doesNotContain("\"refreshToken\":\"");

        mvc.perform(withBearer(get("/api/users/me"), (String) read(token, "$.accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("New.Student@Example.com"))
                .andExpect(jsonPath("$.displayName").value("Test g-new-student"))
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void signingInAgainFindsTheSameAccount() throws Exception {
        Session first = signIn("g-returning", "returning@example.com");
        Session second = signIn("g-returning", "returning@example.com");
        assertThat(second.userId()).isEqualTo(first.userId());
    }

    @Test
    void returnToOutsideThisSiteGoesHomeInstead() throws Exception {
        for (String target : new String[] {"https://evil.example/", "//evil.example/x", "/\\evil.example"}) {
            Started started = start(target);
            MockHttpServletResponse callback = callback(started, "c-" + UUID.randomUUID(),
                    grant(started, "g-redirect", "redirect@example.com"));
            assertThat(callback.getRedirectedUrl()).as(target).isEqualTo(BASE_URL + "/");
        }
    }

    @Test
    void callbackFromAnotherBrowserIsRejected() throws Exception {
        Started started = start("/");
        GOOGLE.willGrant("c-csrf", grant(started, "g-csrf", "csrf@example.com"));
        mvc.perform(get("/api/auth/google/callback").param("code", "c-csrf").param("state", started.state())
                        .cookie(new Cookie("__Host-oauth", "someone-elses-state")))
                .andExpect(status().isFound())
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=invalid_state"));
        mvc.perform(get("/api/auth/google/callback").param("code", "c-csrf").param("state", started.state()))
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=invalid_state"));
    }

    @Test
    void aSignInRequestCanOnlyBeCompletedOnce() throws Exception {
        Started started = start("/");
        assertThat(callback(started, "c-once-1", grant(started, "g-once", "once@example.com")).getRedirectedUrl())
                .isEqualTo(BASE_URL + "/");
        assertThat(callback(started, "c-once-2", grant(started, "g-once", "once@example.com")).getRedirectedUrl())
                .isEqualTo(BASE_URL + "/signin?error=expired");
    }

    @Test
    void idTokenWithTheWrongNonceIsRejected() throws Exception {
        Started started = start("/");
        FakeGoogle.Grant g = grant(started, "g-nonce", "nonce@example.com");
        MockHttpServletResponse callback = callback(started, "c-nonce", new FakeGoogle.Grant(g.codeChallenge(),
                g.subject(), g.email(), true, g.name(), "replayed-nonce", g.audience(), g.issuer()));
        assertThat(callback.getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=invalid_token");
    }

    @Test
    void idTokenForAnotherClientIsRejected() throws Exception {
        Started started = start("/");
        FakeGoogle.Grant g = grant(started, "g-aud", "aud@example.com");
        MockHttpServletResponse callback = callback(started, "c-aud", new FakeGoogle.Grant(g.codeChallenge(),
                g.subject(), g.email(), true, g.name(), g.nonce(), "another-app", g.issuer()));
        assertThat(callback.getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=invalid_token");
    }

    @Test
    void unverifiedGoogleEmailIsRejected() throws Exception {
        Started started = start("/");
        FakeGoogle.Grant g = grant(started, "g-unverified", "unverified@example.com");
        MockHttpServletResponse callback = callback(started, "c-unverified", new FakeGoogle.Grant(g.codeChallenge(),
                g.subject(), g.email(), false, g.name(), g.nonce(), g.audience(), g.issuer()));
        assertThat(callback.getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=email_unverified");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = 'unverified@example.com'", Integer.class))
                .isZero();
    }

    @Test
    void wrongPkceVerifierIsRefusedByGoogle() throws Exception {
        Started started = start("/");
        FakeGoogle.Grant g = grant(started, "g-pkce", "pkce@example.com");
        MockHttpServletResponse callback = callback(started, "c-pkce", new FakeGoogle.Grant("not-the-challenge",
                g.subject(), g.email(), true, g.name(), g.nonce(), g.audience(), g.issuer()));
        assertThat(callback.getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=google_unavailable");
    }

    @Test
    void cancellingAtGoogleShowsAFriendlyMessage() throws Exception {
        mvc.perform(get("/api/auth/google/callback").param("error", "access_denied"))
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo(BASE_URL + "/signin?error=cancelled"));
    }

    @Test
    void refreshRotatesTheTokenAndDetectsReuseOfAnOldOne() throws Exception {
        Session session = signInStudent();

        MockHttpServletResponse first = refresh(session.refreshToken());
        assertThat(first.getStatus()).isEqualTo(200);
        String rotated = setCookie(first, "__Host-rt");
        assertThat(rotated).isNotBlank().isNotEqualTo(session.refreshToken());

        // Moments later (a second tab): still works, without rotating again.
        MockHttpServletResponse concurrent = refresh(session.refreshToken());
        assertThat(concurrent.getStatus()).isEqualTo(200);
        assertThat(setCookie(concurrent, "__Host-rt")).isNull();

        // After the grace period, the old token is a stolen copy: the whole session is revoked.
        Thread.sleep(2_500);
        MockHttpServletResponse reused = refresh(session.refreshToken());
        assertThat(reused.getStatus()).isEqualTo(401);
        assertThat(setCookie(reused, "__Host-rt")).isEmpty();
        assertThat(refresh(rotated).getStatus()).as("newer token in the same family").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REFRESH_TOKEN_REUSE_DETECTED'",
                Integer.class)).isPositive();
    }

    @Test
    void cookieRefreshNeedsTheCsrfHeader() throws Exception {
        Session session = signInStudent();
        mvc.perform(post("/api/auth/token").cookie(new Cookie("__Host-rt", session.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refreshWithoutAnyTokenIsUnauthenticated() throws Exception {
        mvc.perform(post("/api/auth/token").header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void mobileClientsSendAndReceiveTheRefreshTokenInTheBody() throws Exception {
        Session session = signInStudent();
        MockHttpServletResponse response = mvc.perform(post("/api/auth/token")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + session.refreshToken() + "\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((String) read(response, "$.refreshToken")).isNotBlank().isNotEqualTo(session.refreshToken());
        assertThat(setCookie(response, "__Host-rt")).isNull();
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        Session session = signInStudent();
        MockHttpServletResponse out = mvc.perform(post("/api/auth/logout").header("X-Requested-With", "fetch")
                .cookie(new Cookie("__Host-rt", session.refreshToken()))).andReturn().getResponse();
        assertThat(out.getStatus()).isEqualTo(204);
        assertThat(setCookie(out, "__Host-rt")).isEmpty();
        assertThat(refresh(session.refreshToken()).getStatus()).isEqualTo(401);
    }

    @Test
    void jwksPublishesOnlyPublicKeysAndVerifiesIssuedTokens() throws Exception {
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwks).doesNotContain("\"d\"");

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(JWKSet.parse(jwks))));
        Session session = signInStudent();
        Jwt jwt = new NimbusJwtDecoder(processor).decode(session.accessToken());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("https://identity.leenex.internal");
        assertThat(jwt.getAudience()).containsExactly("leenex-platform");
        assertThat(jwt.getSubject()).isEqualTo(session.userId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("STUDENT");
        assertThat(jwt.getClaimAsString("status")).isEqualTo("ACTIVE");
        assertThat(jwt.getExpiresAt()).isBefore(jwt.getIssuedAt().plusSeconds(901));
    }

    @Test
    void protectedEndpointsNeedAnAccessToken() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
