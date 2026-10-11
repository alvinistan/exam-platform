package com.leenex.exam.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The platform security rules, exercised end to end with real signed tokens. */
@SpringBootTest(classes = PlatformSecurityTest.TestApp.class)
@AutoConfigureMockMvc
class PlatformSecurityTest {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound()); // no actuator here: reached MVC, not 401
    }

    @Test
    void noTokenIsUnauthenticatedProblem() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void validStudentTokenReachesTheApiAndExposesTheUserId() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(as(get("/api/me"), TestTokens.student(id)))
                .andExpect(status().isOk())
                .andExpect(content().string(id.toString()));
    }

    @Test
    void serviceSpecificPublicRuleAllowsAnonymousCalls() throws Exception {
        mvc.perform(get("/api/public/catalogue")).andExpect(status().isOk());
    }

    @Test
    void studentCannotReachAdminPaths() throws Exception {
        mvc.perform(as(get("/api/admin/things"), TestTokens.student(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanReachAdminPaths() throws Exception {
        mvc.perform(as(get("/api/admin/things"), TestTokens.admin(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void methodSecurityDenialIsA403ProblemNotA500() throws Exception {
        mvc.perform(as(get("/api/reports"), TestTokens.student(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void userTokensCanNeverReachInternalEndpoints() throws Exception {
        mvc.perform(as(get("/internal/events"), TestTokens.admin(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokensReachInternalEndpoints() throws Exception {
        String token = TestTokens.builder().subject("assessment-service").roles(PlatformJwt.ROLE_SERVICE)
                .scope("catalog.internal").build();
        mvc.perform(as(get("/internal/events"), token)).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = TestTokens.builder().issuedAt(Instant.now().minus(Duration.ofHours(1)))
                .lifetime(Duration.ofMinutes(15)).build();
        mvc.perform(as(get("/api/me"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        mvc.perform(as(get("/api/me"), TestTokens.builder().issuer("https://evil.example").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        mvc.perform(as(get("/api/me"), TestTokens.builder().audience("another-app").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedAccountTokenIsRejected() throws Exception {
        mvc.perform(as(get("/api/me"), TestTokens.builder().status("SUSPENDED").build()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = TestTokens.student(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        mvc.perform(as(get("/api/me"), tampered)).andExpect(status().isUnauthorized());
    }

    @SpringBootApplication(scanBasePackages = "com.leenex.exam.platform")
    static class TestApp {

        @Bean
        JwtDecoder jwtDecoder() {
            return TestTokens.decoder();
        }

        @Bean
        ApiAuthorizationRules publicCatalogue() {
            return rules -> rules.requestMatchers("/api/public/**").permitAll();
        }

        /** Registered explicitly: Spring Boot's test filter skips classes nested in test classes during scanning. */
        @Bean
        SampleController sampleController() {
            return new SampleController();
        }
    }

    @RestController
    static class SampleController {

        @GetMapping("/api/me")
        String me() {
            return CurrentUser.id().toString();
        }

        @GetMapping("/api/public/catalogue")
        String catalogue() {
            return "open";
        }

        @GetMapping("/api/admin/things")
        String adminThings() {
            return "admin";
        }

        @GetMapping("/api/reports")
        @PreAuthorize("hasRole('ADMIN')")
        String reports() {
            return "reports";
        }

        @GetMapping("/internal/events")
        String events() {
            return "events";
        }
    }
}
