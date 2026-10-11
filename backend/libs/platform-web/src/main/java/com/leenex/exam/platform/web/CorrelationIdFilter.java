package com.leenex.exam.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (ADR-014).
 *
 * <p>A well-formed incoming {@code X-Correlation-Id} is kept; anything missing or malformed is replaced with a new
 * UUIDv7. The ID is put in the logging MDC, echoed on the response, and made visible on the request so the gateway
 * forwards it to the services.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UuidV7.generate().toString();

        request.setAttribute(REQUEST_ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(new CorrelatedRequest(request, id), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Current request's correlation ID, or {@code null} outside a request. */
    public static String currentId() {
        return MDC.get(MDC_KEY);
    }

    /** Presents the (possibly replaced) correlation ID as the request header value. */
    private static final class CorrelatedRequest extends HttpServletRequestWrapper {
        private final String id;

        CorrelatedRequest(HttpServletRequest request, String id) {
            super(request);
            this.id = id;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(Set.of(id)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.removeIf(HEADER::equalsIgnoreCase);
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
