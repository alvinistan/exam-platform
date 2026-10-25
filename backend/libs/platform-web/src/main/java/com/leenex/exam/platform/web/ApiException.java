package com.leenex.exam.platform.web;

import java.util.Map;

/**
 * Throw from service code to return a problem response with a stable {@link ProblemCode}.
 * The detail must be safe to show to the caller: no answer keys, internal IDs of other users, or stack traces.
 */
public class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final Map<String, Object> properties;

    public ApiException(ProblemCode code, String detail) {
        this(code, detail, Map.of());
    }

    /** {@code properties} become extra members of the problem body, for example {@code blockers}. */
    public ApiException(ProblemCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ProblemCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
