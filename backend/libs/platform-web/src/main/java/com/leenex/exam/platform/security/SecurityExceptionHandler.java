package com.leenex.exam.platform.security;

import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Method-security denials ({@code @PreAuthorize}) are thrown inside controllers, where the general handler would
 * otherwise report them as 500s. This advice runs first and returns the proper 401/403 problem.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemResponses.of(ProblemCode.FORBIDDEN, "You don't have access to this.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException ex) {
        ProblemDetail problem = ProblemResponses.of(ProblemCode.UNAUTHENTICATED, "Sign in to use this endpoint.");
        return ResponseEntity.status(problem.getStatus()).header("WWW-Authenticate", "Bearer").body(problem);
    }
}
