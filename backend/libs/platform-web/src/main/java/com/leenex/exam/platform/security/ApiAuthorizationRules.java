package com.leenex.exam.platform.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * A service's own URL rules, applied after the platform rules for health, docs, {@code /internal/**} and
 * {@code /api/admin/**}, and before the final "everything else needs a signed-in user". Use it to open endpoints
 * that anonymous visitors may call, such as the public exam catalogue or the sign-in flow.
 */
@FunctionalInterface
public interface ApiAuthorizationRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules);
}
