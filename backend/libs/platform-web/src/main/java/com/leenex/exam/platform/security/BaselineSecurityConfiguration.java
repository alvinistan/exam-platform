package com.leenex.exam.platform.security;

import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 4 security baseline for every service: stateless, no sessions, no form or basic login, and
 * <strong>deny by default</strong>. Only health probes and the OpenAPI document are public.
 *
 * <p>Phase 5 replaces the "authenticated" rule with JWT validation against identity-service's JWKS (ADR-004) and
 * service tokens for {@code /internal/**} (ADR-005). Until then no API endpoint can be reached.
 */
@Configuration(proxyBeanMethods = false)
public class BaselineSecurityConfiguration {

    static final String[] PUBLIC_PATHS = {
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/error"
    };

    @Bean
    SecurityFilterChain baselineSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // Bearer tokens in an Authorization header are not sent automatically by browsers, so CSRF tokens are
            // not needed for the APIs. The refresh-token cookie endpoint gets its own CSRF defence in Phase 5.
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    ProblemResponses.write(response, ProblemCode.UNAUTHENTICATED, "Sign in to use this endpoint."))
                .accessDeniedHandler((request, response, ex) ->
                    ProblemResponses.write(response, ProblemCode.FORBIDDEN, "You don't have access to this.")));
        return http.build();
    }

    /** No password users exist on this platform; this stops Spring Boot generating a default one. */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
