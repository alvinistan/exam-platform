package com.leenex.exam.catalog.config;

import com.leenex.exam.platform.security.ApiAuthorizationRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
public class CatalogSecurityConfig {

    /** Browsing the catalogue needs no sign-in (FR-CAT-09). These endpoints never return question content (FR-CAT-08). */
    @Bean
    ApiAuthorizationRules catalogPublicEndpoints() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/api/categories", "/api/exams", "/api/exams/by-slug/*").permitAll();
    }
}
