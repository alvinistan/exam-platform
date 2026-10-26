package com.leenex.exam.assessment.client;

import com.leenex.exam.assessment.config.AssessmentProperties;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's own access token for internal calls (ADR-005), from identity-service's client-credentials grant.
 * Kept in memory and renewed a minute before it expires.
 */
@Component
public class ServiceTokens {

    private final AssessmentProperties.ServiceClient settings;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private String token;
    private Instant renewAt = Instant.EPOCH;

    public ServiceTokens(AssessmentProperties properties, JsonMapper json, Clock clock) {
        this.settings = properties.serviceClient();
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        this.json = json;
        this.clock = clock;
    }

    public synchronized String token() {
        if (token != null && clock.instant().isBefore(renewAt)) {
            return token;
        }
        if (settings.clientSecret() == null || settings.clientSecret().isBlank()) {
            throw new IllegalStateException("assessment.service-client.client-secret is not set");
        }
        String basic = Base64.getEncoder().encodeToString((URLEncoder.encode(settings.clientId(), StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(settings.clientSecret(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(settings.tokenUri())
                .timeout(settings.timeout())
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Service token refused: HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            token = body.get("access_token").asString();
            long lifetime = body.get("expires_in").asLong();
            renewAt = clock.instant().plusSeconds(Math.max(10, lifetime - 60));
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("Service token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while getting a service token", e);
        }
    }

    /** Forget the token, e.g. after a 401 from another service (keys rotated). */
    public synchronized void invalidate() {
        token = null;
    }
}
