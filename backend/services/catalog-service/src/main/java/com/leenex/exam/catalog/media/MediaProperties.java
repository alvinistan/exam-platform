package com.leenex.exam.catalog.media;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dir         where images are stored
 * @param signingKey  base64 HMAC key for media URLs, at least 32 bytes; empty = a temporary key (local only)
 * @param requireSigningKey refuse to start without a configured key (staging, production)
 * @param urlLifetime how long a signed media URL stays valid
 */
@ConfigurationProperties("catalog.media")
public record MediaProperties(String dir, String signingKey, boolean requireSigningKey, Duration urlLifetime) {

    public MediaProperties {
        if (dir == null || dir.isBlank()) {
            dir = "./data/media";
        }
        if (urlLifetime == null) {
            urlLifetime = Duration.ofMinutes(10);
        }
    }
}
