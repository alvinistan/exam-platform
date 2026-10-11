package com.leenex.exam.gateway;

import com.leenex.exam.platform.security.PlatformJwt;
import com.leenex.exam.platform.security.PlatformSecurityProperties;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Early access-token check (ADR-004): a request that carries a token must carry a valid one, or it stops here.
 * Requests without a token pass through, because some endpoints are public; every service still makes its own
 * decision, so this is defence in depth, not the authorization itself. The verified user ID is used for
 * per-user rate limits.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TokenCheckFilter extends OncePerRequestFilter {

    static final String SUBJECT_ATTRIBUTE = TokenCheckFilter.class.getName() + ".subject";

    private final NimbusJwtDecoder decoder;

    TokenCheckFilter(GatewayProperties properties) {
        if (properties.jwksUri() == null || properties.jwksUri().isBlank()) {
            throw new IllegalStateException("gateway.jwks-uri must be set");
        }
        this.decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).jwsAlgorithm(SignatureAlgorithm.ES256).build();
        this.decoder.setJwtValidator(PlatformJwt.validator(PlatformSecurityProperties.DEFAULT_ISSUER,
                PlatformSecurityProperties.DEFAULT_AUDIENCE));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                Jwt jwt = decoder.decode(header.substring(7).trim());
                request.setAttribute(SUBJECT_ATTRIBUTE, jwt.getSubject());
            } catch (JwtException e) {
                response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                ProblemResponses.write(response, ProblemCode.UNAUTHENTICATED, "Your session has expired. Please sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
