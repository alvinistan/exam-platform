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
public record SubscriptionProperties(@DefaultValue CatalogFeed catalogFeed, @DefaultValue ServiceClient serviceClient,
                                     @DefaultValue Payments payments) {

    /**
     * Payments (ADR-011).
     *
     * @param provider      PAYHERE, or TEST: a clearly labelled stand-in where the student presses "pay" on our own
     *                      page and no money moves. TEST is refused when the "prod" profile is active.
     * @param publicBaseUrl where students reach the web app; return, cancel and notification links are built from it
     *                      (PayHere must be able to reach {@code /api/payments/webhooks/payhere} on it)
     * @param payhere       merchant settings from the PayHere dashboard
     */
    public record Payments(
            @DefaultValue("TEST") String provider,
            @DefaultValue("http://localhost:8088") URI publicBaseUrl,
            @DefaultValue PayHere payhere) {

        public boolean test() {
            return "TEST".equalsIgnoreCase(provider);
        }
    }

    /**
     * @param sandbox   true: sandbox.payhere.lk (test cards, no real money); false: www.payhere.lk
     * @param appId     PayHere API key (Settings > API Keys) for the Refund API; empty = refunds are recorded manually
     * @param appSecret its secret
     */
    public record PayHere(String merchantId, String merchantSecret, @DefaultValue("true") boolean sandbox, String appId,
                          String appSecret) {

        public URI checkoutUrl() {
            return URI.create(sandbox ? "https://sandbox.payhere.lk/pay/checkout" : "https://www.payhere.lk/pay/checkout");
        }

        /** Whether refunds can go through PayHere's Refund API. */
        public boolean refundApi() {
            return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
        }

        private String host() {
            return sandbox ? "https://sandbox.payhere.lk" : "https://www.payhere.lk";
        }

        public URI tokenUrl() {
            return URI.create(host() + "/merchant/v1/oauth/token");
        }

        public URI refundUrl() {
            return URI.create(host() + "/merchant/v1/payment/refund");
        }
    }

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
