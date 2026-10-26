package com.leenex.exam.assessment.client;

import com.leenex.exam.assessment.config.AssessmentProperties;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls to other services with this service's token: JSON in, JSON out, one retry after a 401 (renewed token).
 * Any other failure becomes a 503-style problem for the student ("try again in a moment"); details go to the log.
 */
@Component
public class InternalHttp {

    private static final Logger log = LoggerFactory.getLogger(InternalHttp.class);

    private final ServiceTokens tokens;
    private final JsonMapper json;
    private final HttpClient http;
    private final AssessmentProperties properties;

    public InternalHttp(ServiceTokens tokens, JsonMapper json, AssessmentProperties properties) {
        this.tokens = tokens;
        this.json = json;
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.serviceClient().timeout()).build();
    }

    /** A response that isn't 2xx: the status and the body (a problem document from the other service). */
    public record Failure(int status, JsonNode body) {
    }

    public static final class CallFailed extends RuntimeException {
        private final transient Failure failure;

        CallFailed(Failure failure) {
            super("HTTP " + failure.status());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    public JsonNode get(URI uri) {
        return send(() -> HttpRequest.newBuilder(uri).GET());
    }

    public JsonNode post(URI uri, Object body) {
        String payload = json.writeValueAsString(body);
        return send(() -> HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private JsonNode send(Supplier<HttpRequest.Builder> request) {
        HttpResponse<String> response = once(request);
        if (response.statusCode() == 401) {
            tokens.invalidate();
            response = once(request);
        }
        if (response.statusCode() / 100 != 2) {
            JsonNode body;
            try {
                body = json.readTree(response.body());
            } catch (RuntimeException e) {
                body = null;
            }
            throw new CallFailed(new Failure(response.statusCode(), body));
        }
        return json.readTree(response.body());
    }

    private HttpResponse<String> once(Supplier<HttpRequest.Builder> request) {
        try {
            return http.send(request.get()
                    .timeout(properties.serviceClient().timeout())
                    .header("Authorization", "Bearer " + tokens.token())
                    .header("Accept", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | IllegalStateException e) {
            log.warn("Internal call failed: {}", e.getMessage());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ProblemCode.INTERNAL_ERROR,
                "We couldn't start that right now. Please try again in a moment.");
    }
}
