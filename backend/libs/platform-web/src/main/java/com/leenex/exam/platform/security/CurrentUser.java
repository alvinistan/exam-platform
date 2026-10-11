package com.leenex.exam.platform.security;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The signed-in user of the current request, taken from the verified access token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The user's ID ({@code sub}); throws a 401 problem when the request has no user token. */
    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                // Service tokens carry a client name, not a user ID.
            }
        }
        throw new ApiException(ProblemCode.UNAUTHENTICATED, "Sign in to use this endpoint.");
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
