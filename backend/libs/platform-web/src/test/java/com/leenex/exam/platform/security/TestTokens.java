package com.leenex.exam.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Mints platform access tokens for tests, signed with a throw-away ES256 key. Shared with other modules through
 * the platform-web test-jar.
 */
public final class TestTokens {

    public static final ECKey KEY = generate();
    public static final String ISSUER = PlatformSecurityProperties.DEFAULT_ISSUER;
    public static final String AUDIENCE = PlatformSecurityProperties.DEFAULT_AUDIENCE;

    private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

    private TestTokens() {
    }

    /** A decoder that trusts {@link #KEY} and applies the platform validators. */
    public static JwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK()))));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(PlatformJwt.validator(ISSUER, AUDIENCE));
        return decoder;
    }

    /** JWKS JSON with the public half of {@link #KEY}, as identity-service would publish it. */
    public static String jwksJson() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    public static String student(UUID userId) {
        return builder().subject(userId.toString()).roles(PlatformJwt.ROLE_STUDENT).build();
    }

    public static String admin(UUID userId) {
        return builder().subject(userId.toString()).roles(PlatformJwt.ROLE_STUDENT, PlatformJwt.ROLE_ADMIN).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String subject = UUID.randomUUID().toString();
        private List<String> roles = List.of(PlatformJwt.ROLE_STUDENT);
        private String status = "ACTIVE";
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofMinutes(15);
        private String scope;

        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder roles(String... roles) { this.roles = List.of(roles); return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder issuer(String issuer) { this.issuer = issuer; return this; }
        public Builder audience(String audience) { this.audience = audience; return this; }
        public Builder issuedAt(Instant issuedAt) { this.issuedAt = issuedAt; return this; }
        public Builder lifetime(Duration lifetime) { this.lifetime = lifetime; return this; }
        public Builder scope(String scope) { this.scope = scope; return this; }

        public String build() {
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .subject(subject)
                    .issuedAt(issuedAt)
                    .expiresAt(issuedAt.plus(lifetime))
                    .id(UUID.randomUUID().toString())
                    .claim(PlatformJwt.CLAIM_ROLES, roles)
                    .claim(PlatformJwt.CLAIM_STATUS, status);
            if (scope != null) {
                claims.claim(PlatformJwt.CLAIM_SCOPE, scope);
            }
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(KEY.getKeyID()).build();
            return ENCODER.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        }
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
