package com.leenex.exam.subscription.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * subscription-service settings.
 *
 * @param catalogFeed   where the catalogue's events come from (ADR-006)
 * @param serviceClient this service's client-credentials login at identity-service (ADR-005)
 */
@ConfigurationProperties("subscription")
public record SubscriptionProperties(@DefaultValue CatalogFeed catalogFeed, @DefaultValue ServiceClient serviceClient) {

    /**
     * @param enabled  poll the feed on a schedule (tests call the poller directly)
     * @param baseUrl  catalog-service's address inside the network
     * @param interval pause between polls
     */
    public record CatalogFeed(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("http://localhost:8082") URI baseUrl,
            @DefaultValue("2s") Duration interval,
            @DefaultValue("200") int batchSize) {
    }

    public record ServiceClient(
            @DefaultValue("http://localhost:8081/oauth2/token") URI tokenUri,
            @DefaultValue("subscription-service") String clientId,
            String clientSecret,
            @DefaultValue("2s") Duration timeout) {
    }
}
