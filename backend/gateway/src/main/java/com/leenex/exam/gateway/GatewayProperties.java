package com.leenex.exam.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway settings.
 *
 * @param jwksUri    identity-service's public keys, for the early token check
 * @param rateLimits in-memory token buckets (ADR-015); correct while there is one gateway instance
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String jwksUri, @DefaultValue List<RateLimit> rateLimits) {

    /**
     * @param paths          path patterns, e.g. {@code /api/auth/**}
     * @param methods        HTTP methods; empty means all
     * @param key            {@code IP}, or {@code USER} (signed-in user, falling back to IP)
     * @param capacity       burst size
     * @param refillPerMinute steady rate
     */
    public record RateLimit(String name, List<String> paths, @DefaultValue List<String> methods,
                            @DefaultValue("IP") Key key, int capacity, int refillPerMinute) {
    }

    public enum Key { IP, USER }
}
