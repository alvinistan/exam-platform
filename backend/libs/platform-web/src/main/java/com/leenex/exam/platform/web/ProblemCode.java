package com.leenex.exam.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Stable, non-sensitive reason codes returned in every error response (contracts/openapi/common.yaml, Problem.code).
 * Clients switch on these; the human-readable title and detail may change.
 */
public enum ProblemCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign-in required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not allowed"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Account suspended"),
    SUBSCRIPTION_REQUIRED(HttpStatus.FORBIDDEN, "Subscription required"),
    SUBSCRIPTION_EXPIRED(HttpStatus.FORBIDDEN, "Subscription expired"),
    PAYMENT_PENDING(HttpStatus.FORBIDDEN, "Payment pending"),
    FREE_ALLOWANCE_USED(HttpStatus.FORBIDDEN, "Free allowance used"),
    MODE_DISABLED(HttpStatus.FORBIDDEN, "Mode not available"),
    ATTEMPT_EXPIRED(HttpStatus.CONFLICT, "Attempt expired"),
    ATTEMPT_FINISHED(HttpStatus.CONFLICT, "Attempt already finished"),
    INSUFFICIENT_QUESTIONS(HttpStatus.UNPROCESSABLE_CONTENT, "Not enough questions"),
    EXAM_UNAVAILABLE(HttpStatus.NOT_FOUND, "Exam unavailable"),
    SLUG_TAKEN(HttpStatus.CONFLICT, "URL already in use"),
    PUBLISH_BLOCKED(HttpStatus.CONFLICT, "Cannot publish yet"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

    /** Base for the problem {@code type} URI; one stable URI per code. */
    public static final String TYPE_BASE = "https://leenexlabs.example/problems/";

    private final HttpStatus status;
    private final String title;

    ProblemCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return TYPE_BASE + name().toLowerCase().replace('_', '-');
    }
}
