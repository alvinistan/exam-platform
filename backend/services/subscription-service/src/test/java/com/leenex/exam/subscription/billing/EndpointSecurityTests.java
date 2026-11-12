package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.security.EndpointSecurityMatrix;
import com.leenex.exam.platform.security.TestTokens;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Phase 14: every subscription-service endpoint is protected unless listed here as public. */
class EndpointSecurityTests extends PaymentTestSupport {

    /** The PayHere webhook is public; a message is only trusted when its md5sig is right. */
    static final Set<String> PUBLIC = Set.of(
            "GET /api/access/exams",
            "GET /api/plans",
            "GET /api/plans/offers",
            "POST /api/payments/webhooks/payhere");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyEndpointIsProtectedUnlessPublic() throws Exception {
        EndpointSecurityMatrix.verify(mvc, mapping, PUBLIC, Map.of(),
                TestTokens.student(UUID.randomUUID()), ADMIN);
    }
}
