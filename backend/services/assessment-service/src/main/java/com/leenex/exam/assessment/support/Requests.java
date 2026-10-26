package com.leenex.exam.assessment.support;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Small helpers shared by the controllers: optimistic-lock headers, field errors and the optional caller. */
public final class Requests {

    private Requests() {
    }

    /** Accepts {@code "3"}, {@code 3} or {@code W/"3"}, as browsers and tools send ETags differently. */
    public static int ifMatchVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match header with the record version is required.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        try {
            return Integer.parseInt(v.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match must be the record version.");
        }
    }

    public static ApiException staleVersion() {
        return new ApiException(ProblemCode.CONFLICT, "Someone else changed this since you opened it. Reload and try again.");
    }

    public static ApiException invalid(List<Map<String, String>> errors) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "One or more fields are invalid.", Map.of("errors", errors));
    }

    public static ApiException invalid(String field, String message) {
        return invalid(List.of(Map.of("field", field, "message", message)));
    }

    /** The signed-in student on a public endpoint, or null for an anonymous visitor (or a service token). */
    public static UUID optionalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
        }
        return null;
    }
}
