package com.leenex.exam.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * The platform access-token format (ADR-004) and how every service checks it.
 *
 * <p>Claims: {@code iss}, {@code aud}, {@code sub} (user ID), {@code roles} (STUDENT, ADMIN; SERVICE for service
 * tokens), {@code status} (ACTIVE or SUSPENDED), {@code scope} (service tokens only), {@code iat}, {@code exp},
 * {@code jti}. Signed with ES256 by identity-service.
 */
public final class PlatformJwt {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_STATUS = "status";
    public static final String CLAIM_SCOPE = "scope";
    public static final String ROLE_STUDENT = "STUDENT";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SERVICE = "SERVICE";

    private PlatformJwt() {
    }

    /** Checks besides the signature: expiry (60 s clock skew), issuer, audience, and an active account. */
    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceCheck = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        OAuth2TokenValidator<Jwt> activeAccount = jwt -> "ACTIVE".equals(jwt.getClaimAsString(CLAIM_STATUS))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Account is not active", null));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceCheck, activeAccount);
    }

    /** Maps {@code roles} to {@code ROLE_*} and {@code scope} to {@code SCOPE_*} authorities. */
    public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(PlatformJwt::authorities);
        return converter;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        String scope = jwt.getClaimAsString(CLAIM_SCOPE);
        if (scope != null && !scope.isBlank()) {
            for (String s : scope.trim().split("\\s+")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return authorities;
    }
}
