package com.leenex.exam.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem bodies in the shape every service contract declares. */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static ProblemDetail of(ProblemCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        String correlationId = CorrelationIdFilter.currentId();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    /**
     * Writes a problem directly to the servlet response. Used where no MVC handler runs, such as the security
     * filter chain's authentication entry point.
     */
    public static void write(HttpServletResponse response, ProblemCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        StringBuilder json = new StringBuilder(256).append('{');
        field(json, "type", code.typeUri()).append(',');
        field(json, "title", code.title()).append(',');
        json.append("\"status\":").append(code.status().value()).append(',');
        field(json, "code", code.name());
        if (detail != null) {
            field(json.append(','), "detail", detail);
        }
        String correlationId = CorrelationIdFilter.currentId();
        if (correlationId != null) {
            field(json.append(','), "correlationId", correlationId);
        }
        response.getWriter().write(json.append('}').toString());
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"');
    }
}
