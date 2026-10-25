package com.leenex.exam.identity.token;

import com.leenex.exam.identity.config.IdentityProperties;
import com.leenex.exam.platform.security.PlatformJwt;
import com.leenex.exam.platform.security.PlatformSecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Issues platform access tokens (ADR-004) and verifies them locally for identity-service's own endpoints. */
@Component
public class AccessTokens {

    private final SigningKeys keys;
    private final JwtEncoder encoder;
    private final PlatformSecurityProperties platform;
    private final Duration ttl;
    private final Clock clock;

    public AccessTokens(SigningKeys keys, PlatformSecurityProperties platform, IdentityProperties properties, Clock clock) {
        this.keys = keys;
        this.platform = platform;
        this.ttl = properties.jwt().accessTokenTtl();
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(keys.privateSet()));
    }

    public record Issued(String token, long expiresInSeconds) {
    }

    public Issued issue(UUID userId, List<String> roles, String status) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(platform.issuer())
                .audience(List.of(platform.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(PlatformJwt.CLAIM_ROLES, roles)
                .claim(PlatformJwt.CLAIM_STATUS, status)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKey().getKeyID()).build();
        return new Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), ttl.toSeconds());
    }

    /**
     * A service token (ADR-005): role SERVICE, the granted scopes, and the client ID as subject. It can reach
     * {@code /internal/**} but never acts for a user.
     */
    public Issued issueService(String clientId, List<String> scopes, Duration lifetime) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(platform.issuer())
                .audience(List.of(platform.audience()))
                .subject(clientId)
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .id(UUID.randomUUID().toString())
                .claim(PlatformJwt.CLAIM_ROLES, List.of(PlatformJwt.ROLE_SERVICE))
                .claim(PlatformJwt.CLAIM_STATUS, "ACTIVE")
                .claim(PlatformJwt.CLAIM_SCOPE, String.join(" ", scopes))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKey().getKeyID()).build();
        return new Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), lifetime.toSeconds());
    }

    /** Verifies with the local public keys, so identity-service never calls its own JWKS endpoint. */
    public JwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(keys.publicSet())));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(PlatformJwt.validator(platform.issuer(), platform.audience()));
        return decoder;
    }
}
