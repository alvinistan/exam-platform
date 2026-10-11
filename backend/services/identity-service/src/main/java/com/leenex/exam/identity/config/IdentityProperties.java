package com.leenex.exam.identity.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * identity-service settings. Secrets (Google client secret, signing keys) come only from the environment.
 *
 * @param publicBaseUrl      where users reach the web app; sign-in redirects back here
 * @param adminBootstrapEmail the Google email that becomes the first administrator, once (FR-ID-04)
 */
@ConfigurationProperties("identity")
public record IdentityProperties(
        @DefaultValue("http://localhost:8088") URI publicBaseUrl,
        String adminBootstrapEmail,
        @DefaultValue Google google,
        @DefaultValue Jwt jwt,
        @DefaultValue Refresh refresh) {

    /** Google OpenID Connect client (web application type). */
    public record Google(
            String clientId,
            String clientSecret,
            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") URI authorizationUri,
            @DefaultValue("https://oauth2.googleapis.com/token") URI tokenUri,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") URI jwkSetUri,
            @DefaultValue({"https://accounts.google.com", "accounts.google.com"}) List<String> issuers,
            @DefaultValue("10m") Duration loginTimeout) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /**
     * Access tokens.
     *
     * @param signingKeys          JWK set JSON with private EC P-256 keys; the first signs, all are published
     * @param requireConfiguredKey refuse to start without {@code signingKeys} (staging and production)
     */
    public record Jwt(
            String signingKeys,
            @DefaultValue("false") boolean requireConfiguredKey,
            @DefaultValue("15m") Duration accessTokenTtl) {
    }

    /**
     * Refresh tokens.
     *
     * @param reuseGrace a rotated token presented again within this window (two tabs refreshing at once) gets an
     *                   access token without rotation instead of revoking the session
     */
    public record Refresh(
            @DefaultValue("30d") Duration ttl,
            @DefaultValue("30s") Duration reuseGrace) {
    }
}
