package com.leenex.exam.platform.web;

/**
 * Throw from service code to return a problem response with a stable {@link ProblemCode}.
 * The detail must be safe to show to the caller: no answer keys, internal IDs of other users, or stack traces.
 */
public class ApiException extends RuntimeException {

    private final ProblemCode code;

    public ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() {
        return code;
    }
}
