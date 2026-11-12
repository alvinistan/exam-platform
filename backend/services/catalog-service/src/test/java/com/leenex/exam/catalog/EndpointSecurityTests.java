package com.leenex.exam.catalog;

import com.leenex.exam.platform.security.EndpointSecurityMatrix;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Phase 14: every catalog-service endpoint is protected unless listed here as public. */
class EndpointSecurityTests extends CatalogTestSupport {

    static final Set<String> PUBLIC = Set.of(
            "GET /api/categories",
            "GET /api/exams",
            "GET /api/exams/by-slug/{slug}",
            "GET /api/media/{key:[a-f0-9]{32}\\.(?:png|jpg)}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyEndpointIsProtectedUnlessPublic() throws Exception {
        EndpointSecurityMatrix.verify(mvc, mapping, PUBLIC,
                Map.of("key", "0123456789abcdef0123456789abcdef.png", "mode", "PRACTICE"), STUDENT, ADMIN);
    }
}
