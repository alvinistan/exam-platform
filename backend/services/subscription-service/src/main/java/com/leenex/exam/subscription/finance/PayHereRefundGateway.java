package com.leenex.exam.subscription.finance;

import com.leenex.exam.subscription.config.SubscriptionProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PayHere's Refund API: an OAuth token from the API key (App ID and secret), then a refund by PayHere payment id.
 * The token is kept until shortly before it expires. Live keys only work from the domain and IP PayHere whitelisted.
 */
@Component
public class PayHereRefundGateway implements RefundGateway {

    private static final Logger log = LoggerFactory.getLogger(PayHereRefundGateway.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final SubscriptionProperties.PayHere settings;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String token;
    private Instant renewAt = Instant.EPOCH;

    public PayHereRefundGateway(SubscriptionProperties properties, JsonMapper json, Clock clock) {
        this.settings = properties.payments().payhere();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public boolean available() {
        return settings.refundApi();
    }

    @Override
    public Result refund(String paymentId, String amount, String description) {
        if (!available()) {
            return new Result(false, null, "PayHere refunds aren't set up (no API key).");
        }
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("payment_id", paymentId);
            body.put("description", description);
            if (amount != null) {
                body.put("amount", amount);
            }
            HttpResponse<String> response = send(body, false);
            JsonNode reply = json.readTree(response.body());
            if (reply.has("error")) {
                // An expired or revoked token: get a new one once.
                response = send(body, true);
                reply = json.readTree(response.body());
            }
            int status = reply.path("status").asInt(Integer.MIN_VALUE);
            String message = reply.path("msg").asString(reply.path("error_description").asString("No message"));
            if (status == 1) {
                JsonNode data = reply.path("data");
                return new Result(true, data.isNull() || data.isMissingNode() ? null : data.asString(), message);
            }
            log.warn("PayHere refused a refund: status {} ({})", status, message);
            return new Result(false, null, status == -2
                    ? "PayHere refused the API key for this server (check the whitelisted domain and IP)."
                    : "PayHere: " + message);
        } catch (IOException | RuntimeException e) {
            log.warn("PayHere refund request failed", e);
            return new Result(false, null, "Couldn't reach PayHere. Check the payment in PayHere before trying again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "Interrupted while contacting PayHere.");
        }
    }

    private HttpResponse<String> send(Map<String, String> body, boolean newToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(settings.refundUrl())
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token(newToken))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private synchronized String token(boolean renew) throws IOException, InterruptedException {
        if (!renew && token != null && clock.instant().isBefore(renewAt)) {
            return token;
        }
        String basic = Base64.getEncoder()
                .encodeToString((settings.appId() + ":" + settings.appSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(settings.tokenUrl())
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("PayHere refused the API key: HTTP " + response.statusCode());
        }
        JsonNode reply = json.readTree(response.body());
        token = reply.path("access_token").asString();
        renewAt = clock.instant().plusSeconds(Math.max(10, reply.path("expires_in").asLong(600) - 60));
        return token;
    }
}
