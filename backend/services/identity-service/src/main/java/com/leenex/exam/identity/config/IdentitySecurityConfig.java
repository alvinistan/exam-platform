package com.leenex.exam.identity.config;

import com.leenex.exam.identity.token.AccessTokens;
import com.leenex.exam.platform.security.ApiAuthorizationRules;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfig {

    /** identity-service verifies tokens with its own keys instead of fetching its own JWKS. */
    @Bean
    JwtDecoder jwtDecoder(AccessTokens accessTokens) {
        return accessTokens.decoder();
    }

    /** The sign-in flow, the public keys and the service-token endpoint are reachable without an access token. */
    @Bean
    ApiAuthorizationRules identityPublicEndpoints() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/api/auth/google/start", "/api/auth/google/callback",
                        "/.well-known/jwks.json").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/token", "/api/auth/logout").permitAll()
                // Service clients authenticate with their own credentials; the gateway never routes this path.
                .requestMatchers(HttpMethod.POST, "/oauth2/token").permitAll();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
