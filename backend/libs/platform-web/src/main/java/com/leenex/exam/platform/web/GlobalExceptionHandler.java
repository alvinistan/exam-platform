package com.leenex.exam.platform.web;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into the platform's problem shape: {@code type, title, status, code, detail, correlationId,
 * errors}. Unexpected exceptions are logged with the correlation ID and returned without internal detail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return respond(ProblemResponses.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemResponses.of(ProblemCode.VALIDATION_FAILED, "One or more values are invalid.");
        problem.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList());
        return respond(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ProblemResponses.of(ProblemCode.INTERNAL_ERROR, null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemResponses.of(ProblemCode.VALIDATION_FAILED, "One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(),
                        "message", e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return new ResponseEntity<>(problem, headers, problem.getStatus());
    }

    /** Framework exceptions (404, 405, 415, malformed JSON …) get a code and correlation ID too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemCode code = codeFor(statusCode);
        String detail = body instanceof ProblemDetail pd ? pd.getDetail() : null;
        ProblemDetail problem = ProblemResponses.of(code, detail);
        problem.setStatus(statusCode.value());
        return new ResponseEntity<>(problem, headers, statusCode);
    }

    static ProblemCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemCode.VALIDATION_FAILED;
            case 401 -> ProblemCode.UNAUTHENTICATED;
            case 403 -> ProblemCode.FORBIDDEN;
            case 404 -> ProblemCode.NOT_FOUND;
            case 405 -> ProblemCode.METHOD_NOT_ALLOWED;
            case 409 -> ProblemCode.CONFLICT;
            case 415 -> ProblemCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ProblemCode.RATE_LIMITED;
            default -> status.is5xxServerError() ? ProblemCode.INTERNAL_ERROR : ProblemCode.VALIDATION_FAILED;
        };
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
