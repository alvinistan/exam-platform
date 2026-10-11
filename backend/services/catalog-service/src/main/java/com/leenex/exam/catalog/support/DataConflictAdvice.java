package com.leenex.exam.catalog.support;

import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Services check uniqueness before writing, so a constraint violation here means two administrators changed the
 * same thing at the same moment. The whole transaction has already rolled back; answer 409 rather than 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataConflictAdvice {

    private static final Logger log = LoggerFactory.getLogger(DataConflictAdvice.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemResponses.of(ProblemCode.CONFLICT,
                "This change conflicts with another one saved at the same time. Reload and try again.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
