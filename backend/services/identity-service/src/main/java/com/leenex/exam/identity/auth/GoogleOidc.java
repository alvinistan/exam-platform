package com.leenex.exam.identity.auth;

import com.leenex.exam.identity.config.IdentityProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OpenID Connect, authorization-code flow with PKCE and nonce (ADR-004). Only the parts the platform needs:
 * build the authorization URL, exchange the code, and verify the ID token's signature, issuer, audience, expiry
 * and nonce. The ID token's claims are used once to find or create the account and are not kept.
 */
@Component
public class GoogleOidc {

    private final IdentityProperties.Google google;
    private final URI redirectUri;
    private final RestClient http;
    private final NimbusJwtDecoder idTokenDecoder;

    public GoogleOidc(IdentityProperties properties) {
        this.google = properties.google();
        this.redirectUri = properties.publicBaseUrl().resolve("/api/auth/google/callback");
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requests).build();
        // Google's signing keys are fetched once and cached by the decoder; the nonce is checked per sign-in below.
        this.idTokenDecoder = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> google.issuers().contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success() : failure("Unexpected issuer");
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(google.clientId())
                ? OAuth2TokenValidatorResult.success() : failure("Token was issued for another client");
        this.idTokenDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience));
    }

    public boolean configured() {
        return google.configured();
    }

    public URI authorizationUrl(LoginRequests.Pending pending) {
        return UriComponentsBuilder.fromUri(google.authorizationUri())
                .queryParam("client_id", google.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", pending.state())
                .queryParam("nonce", pending.nonce())
                .queryParam("code_challenge", pending.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUri();
    }

    /** The verified identity from Google. */
    public record GoogleIdentity(String subject, String email, String name, String picture) {
    }

    public GoogleIdentity exchange(String code, LoginRequests.Pending pending) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri.toString());
        form.add("client_id", google.clientId());
        form.add("client_secret", google.clientSecret());
        form.add("code_verifier", pending.codeVerifier());

        Map<?, ?> response;
        try {
            response = http.post().uri(google.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new SignInException("google_unavailable", "Token exchange with Google failed", e);
        }
        Object idToken = response == null ? null : response.get("id_token");
        if (!(idToken instanceof String token)) {
            throw new SignInException("google_unavailable", "Google returned no ID token");
        }

        Jwt jwt;
        try {
            jwt = idTokenDecoder.decode(token);
        } catch (JwtException e) {
            throw new SignInException("invalid_token", "Google ID token rejected: " + e.getMessage(), e);
        }
        if (!pending.nonce().equals(jwt.getClaimAsString("nonce"))) {
            throw new SignInException("invalid_token", "Google ID token nonce does not match this sign-in");
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) || jwt.getClaimAsString("email") == null) {
            throw new SignInException("email_unverified", "Google account email is not verified");
        }
        String name = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");
        return new GoogleIdentity(jwt.getSubject(), email,
                name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : truncate(name, 120),
                jwt.getClaimAsString("picture"));
    }

    private static OAuth2TokenValidatorResult failure(String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
