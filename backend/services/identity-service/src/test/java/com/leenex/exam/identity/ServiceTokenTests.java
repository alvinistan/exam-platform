package com.leenex.exam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Client-credentials tokens for backend services (ADR-005). */
class ServiceTokenTests extends IdentityTestSupport {

    @Autowired
    JwtDecoder decoder;

    private MockHttpServletResponse token(String basic, String form) throws Exception {
        var request = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form);
        if (basic != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void aConfiguredServiceGetsAShortLivedServiceToken() throws Exception {
        MockHttpServletResponse r = token("test-service:" + SERVICE_SECRET, "grant_type=client_credentials");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat((String) read(r, "$.token_type")).isEqualTo("Bearer");
        assertThat((Integer) read(r, "$.expires_in")).isEqualTo(300);
        assertThat((String) read(r, "$.scope")).isEqualTo("catalog.internal billing.access");

        Jwt jwt = decoder.decode(read(r, "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("test-service");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("catalog.internal billing.access");
    }

    @Test
    void credentialsCanBeSentInTheFormAndScopesNarrowed() throws Exception {
        MockHttpServletResponse r = token(null,
                "grant_type=client_credentials&client_id=test-service&client_secret=" + SERVICE_SECRET + "&scope=catalog.internal");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat((String) read(r, "$.scope")).isEqualTo("catalog.internal");
        assertThat(token(null, "grant_type=client_credentials&client_id=test-service&client_secret=" + SERVICE_SECRET
                + "&scope=admin.everything").getStatus()).isEqualTo(400);
    }

    @Test
    void wrongOrMissingCredentialsAreRefused() throws Exception {
        MockHttpServletResponse wrong = token("test-service:not-the-secret", "grant_type=client_credentials");
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat((String) read(wrong, "$.error")).isEqualTo("invalid_client");
        assertThat(token("unknown-service:" + SERVICE_SECRET, "grant_type=client_credentials").getStatus()).isEqualTo(401);
        assertThat(token(null, "grant_type=client_credentials").getStatus()).isEqualTo(401);
        // Clients without a configured secret are switched off.
        assertThat(token("subscription-service:", "grant_type=client_credentials").getStatus()).isEqualTo(401);
    }

    @Test
    void onlyTheClientCredentialsGrantIsSupported() throws Exception {
        MockHttpServletResponse r = token("test-service:" + SERVICE_SECRET, "grant_type=password&username=a&password=b");
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat((String) read(r, "$.error")).isEqualTo("unsupported_grant_type");
    }

    @Test
    void serviceTokensCannotActAsUsers() throws Exception {
        String access = read(token("test-service:" + SERVICE_SECRET, "grant_type=client_credentials"), "$.access_token");
        assertThat(mvc.perform(withBearer(get("/api/admin/users"), access)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(List.of(401, 403)).contains(
                mvc.perform(withBearer(get("/api/users/me"), access)).andReturn().getResponse().getStatus());
    }
}
