package com.leenex.exam.subscription.catalogfeed;

import com.leenex.exam.subscription.config.SubscriptionProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads catalog-service's event feed: {@code GET /internal/events?after=<seq>} (ADR-006). */
@Component
public class CatalogFeedClient {

    public record FeedEvent(long seq, UUID id, String type, OffsetDateTime occurredAt, JsonNode payload) {
    }

    private final SubscriptionProperties properties;
    private final ServiceTokens tokens;
    private final JsonMapper json;
    private final HttpClient http;

    public CatalogFeedClient(SubscriptionProperties properties, ServiceTokens tokens, JsonMapper json) {
        this.properties = properties;
        this.tokens = tokens;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.serviceClient().timeout()).build();
    }

    public List<FeedEvent> after(long seq) {
        URI uri = URI.create(properties.catalogFeed().baseUrl().toString().replaceAll("/+$", "")
                + "/internal/events?after=" + seq + "&limit=" + properties.catalogFeed().batchSize());
        HttpResponse<String> response = get(uri);
        if (response.statusCode() == 401) {
            tokens.invalidate();
            response = get(uri);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Catalogue feed answered HTTP " + response.statusCode());
        }
        List<FeedEvent> events = new ArrayList<>();
        for (JsonNode e : json.readTree(response.body())) {
            events.add(new FeedEvent(e.get("seq").asLong(), UUID.fromString(e.get("id").asString()),
                    e.get("type").asString(), OffsetDateTime.parse(e.get("occurredAt").asString()), e.get("payload")));
        }
        return events;
    }

    private HttpResponse<String> get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.serviceClient().timeout())
                .header("Authorization", "Bearer " + tokens.token())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Catalogue feed request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the catalogue feed", e);
        }
    }
}
