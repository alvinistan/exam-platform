package com.leenex.exam.platform.security;

import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security for every service (ADR-004, ADR-005). Stateless; no sessions, forms or basic login.
 *
 * <ol>
 *   <li>Health probes and the OpenAPI document are public.</li>
 *   <li>{@code /internal/**} needs a service token (role SERVICE); user tokens can never reach it.</li>
 *   <li>{@code /api/admin/**} needs the ADMIN role.</li>
 *   <li>Each service's {@link ApiAuthorizationRules} (for example, public catalogue reads).</li>
 *   <li>Everything else needs a valid access token from an active account.</li>
 * </ol>
 *
 * <p>Tokens are verified against identity-service's JWKS ({@code platform.security.jwks-uri}). identity-service
 * verifies with its own keys instead and defines its own {@link JwtDecoder}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityConfiguration {

    static final String[] PUBLIC_PATHS = {
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/error"
    };

    @Bean
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, List<ApiAuthorizationRules> serviceRules)
            throws Exception {
        http
            // Access tokens travel in the Authorization header, which browsers never attach on their own, so
            // CSRF tokens add nothing here. The one cookie endpoint (token refresh) has its own CSRF defence.
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rules -> {
                rules.requestMatchers(PUBLIC_PATHS).permitAll();
                rules.requestMatchers("/internal/**").hasRole(PlatformJwt.ROLE_SERVICE);
                rules.requestMatchers("/api/admin/**").hasRole(PlatformJwt.ROLE_ADMIN);
                serviceRules.forEach(r -> r.configure(rules));
                rules.anyRequest().authenticated();
            })
            .oauth2ResourceServer(o -> o
                .jwt(jwt -> jwt.jwtAuthenticationConverter(PlatformJwt.authenticationConverter()))
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setHeader("WWW-Authenticate", "Bearer");
                    ProblemResponses.write(response, ProblemCode.UNAUTHENTICATED, "Sign in to use this endpoint.");
                }))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setHeader("WWW-Authenticate", "Bearer");
                    ProblemResponses.write(response, ProblemCode.UNAUTHENTICATED, "Sign in to use this endpoint.");
                })
                .accessDeniedHandler((request, response, ex) ->
                    ProblemResponses.write(response, ProblemCode.FORBIDDEN, "You don't have access to this.")));
        return http.build();
    }

    /** Verifies tokens with identity-service's published keys; keys are fetched lazily and cached. */
    @Bean
    @ConditionalOnProperty("platform.security.jwks-uri")
    JwtDecoder platformJwtDecoder(PlatformSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(PlatformJwt.validator(properties.issuer(), properties.audience()));
        return decoder;
    }

    /** No password users exist on this platform; this stops Spring Boot generating a default one. */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
