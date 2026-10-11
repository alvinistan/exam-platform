package com.leenex.exam.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token settings shared by every service.
 *
 * @param issuer   expected {@code iss}; identity-service signs with the same value (a URL, as OIDC expects)
 * @param audience expected {@code aud}
 * @param jwksUri  identity-service's public keys; every service except identity-service sets it
 */
@ConfigurationProperties("platform.security")
public record PlatformSecurityProperties(String issuer, String audience, String jwksUri) {

    public static final String DEFAULT_ISSUER = "https://identity.leenex.internal";
    public static final String DEFAULT_AUDIENCE = "leenex-platform";

    public PlatformSecurityProperties {
        issuer = issuer == null || issuer.isBlank() ? DEFAULT_ISSUER : issuer;
        audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
    }
}
