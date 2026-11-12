package com.leenex.exam.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Phase 14: calls every endpoint a service has, so a new endpoint can't ship without protection by accident.
 *
 * <ul>
 *   <li>Without a token (or with a forged one) every endpoint answers 401, except the ones the service lists as public.
 *       Each listed public endpoint must exist, so the list can't go stale.</li>
 *   <li>{@code /api/admin/**} refuses a student token (403).</li>
 *   <li>{@code /internal/**} refuses student and admin tokens (403); only service tokens may call it.</li>
 * </ul>
 *
 * Endpoints are written as {@code "GET /api/exams/by-slug/{slug}"}, exactly as mapped. Path variables are filled with a
 * random UUID unless {@code samples} gives a value for that variable name.
 */
public final class EndpointSecurityMatrix {

    /** A mapped endpoint, e.g. {@code GET /api/exams/{examId}}. */
    public record Endpoint(String method, String pattern) implements Comparable<Endpoint> {
        @Override
        public String toString() {
            return method + " " + pattern;
        }

        @Override
        public int compareTo(Endpoint o) {
            return toString().compareTo(o.toString());
        }
    }

    private EndpointSecurityMatrix() {
    }

    /** Every endpoint of the service's own controllers (springdoc and the error page left out). */
    public static Set<Endpoint> endpoints(RequestMappingHandlerMapping mapping) {
        Set<Endpoint> all = new TreeSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/v3/api-docs") || pattern.startsWith("/swagger-ui") || pattern.equals("/error")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    all.add(new Endpoint("GET", pattern));
                } else {
                    methods.forEach(m -> all.add(new Endpoint(m.name(), pattern)));
                }
            }
        }
        return all;
    }

    /**
     * Runs the checks and fails with every problem found at once.
     *
     * @param publicEndpoints endpoints that may be called without signing in, e.g. {@code "GET /api/plans"}
     * @param samples         values for path variables, by variable name
     */
    public static void verify(MockMvc mvc, RequestMappingHandlerMapping mapping, Set<String> publicEndpoints,
                              Map<String, String> samples, String studentToken, String adminToken) throws Exception {
        Set<Endpoint> endpoints = endpoints(mapping);
        assertThat(endpoints).as("endpoints found").isNotEmpty();
        Set<String> names = new TreeSet<>();
        endpoints.forEach(e -> names.add(e.toString()));
        assertThat(names).as("every public endpoint listed in the test exists").containsAll(publicEndpoints);

        String forged = TestTokens.builder().issuer("https://attacker.example").build();
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints) {
            String path = fill(e.pattern(), samples);
            if (!publicEndpoints.contains(e.toString())) {
                expect(problems, e, "no token", mvc.perform(request(e, path, null)).andReturn().getResponse()
                        .getStatus(), 401);
                expect(problems, e, "token from another issuer", mvc.perform(request(e, path, forged)).andReturn()
                        .getResponse().getStatus(), 401);
            }
            if (e.pattern().startsWith("/api/admin/")) {
                expect(problems, e, "student token", mvc.perform(request(e, path, studentToken)).andReturn()
                        .getResponse().getStatus(), 403);
            }
            if (e.pattern().startsWith("/internal/")) {
                expect(problems, e, "student token", mvc.perform(request(e, path, studentToken)).andReturn()
                        .getResponse().getStatus(), 403);
                expect(problems, e, "admin token", mvc.perform(request(e, path, adminToken)).andReturn()
                        .getResponse().getStatus(), 403);
            }
        }
        assertThat(problems).as("endpoint protection problems").isEmpty();
    }

    private static void expect(List<String> problems, Endpoint e, String caller, int status, int expected) {
        if (status != expected) {
            problems.add(e + " with " + caller + ": expected " + expected + " but got " + status);
        }
    }

    private static MockHttpServletRequestBuilder request(Endpoint e, String path, String token) {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.request(HttpMethod.valueOf(e.method()),
                java.net.URI.create(path));
        if (token != null) {
            r.header("Authorization", "Bearer " + token);
        }
        return r;
    }

    /** Replaces each {name} or {name:regex} (the regex may contain braces) with a sample value. */
    static String fill(String pattern, Map<String, String> samples) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int depth = 0;
            int end = i;
            for (; end < pattern.length(); end++) {
                if (pattern.charAt(end) == '{') {
                    depth++;
                } else if (pattern.charAt(end) == '}' && --depth == 0) {
                    break;
                }
            }
            String variable = pattern.substring(i + 1, end);
            int colon = variable.indexOf(':');
            String name = colon < 0 ? variable : variable.substring(0, colon);
            out.append(samples.getOrDefault(name, UUID.randomUUID().toString()));
            i = end + 1;
        }
        return out.toString();
    }
}
