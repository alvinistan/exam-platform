package com.leenex.exam.identity;

import com.leenex.exam.platform.security.EndpointSecurityMatrix;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Phase 14: every identity-service endpoint is protected unless listed here as public. */
class EndpointSecurityTests extends IdentityTestSupport {

    /** Sign-in, token refresh (cookie plus CSRF header), sign-out, public keys and service logins. */
    static final Set<String> PUBLIC = Set.of(
            "GET /api/auth/google/start",
            "GET /api/auth/google/callback",
            "POST /api/auth/token",
            "POST /api/auth/logout",
            "GET /.well-known/jwks.json",
            "POST /oauth2/token");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyEndpointIsProtectedUnlessPublic() throws Exception {
        EndpointSecurityMatrix.verify(mvc, mapping, PUBLIC, Map.of(), signInStudent().accessToken(),
                signInAdmin().accessToken());
    }
}
