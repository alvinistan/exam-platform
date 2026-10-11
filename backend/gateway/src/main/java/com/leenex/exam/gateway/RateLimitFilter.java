package com.leenex.exam.gateway;

import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Token-bucket rate limits on sensitive routes (ADR-015): sign-in, checkout and attempt creation. Buckets live in
 * memory, which is correct for a single gateway instance; a second instance moves them to Redis.
 * Over the limit, the caller gets 429 with {@code Retry-After}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RateLimitFilter extends OncePerRequestFilter {

    private final List<CompiledRule> rules;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    @Autowired
    RateLimitFilter(GatewayProperties properties) {
        this(properties, System::nanoTime);
    }

    RateLimitFilter(GatewayProperties properties, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.rules = properties.rateLimits().stream().map(CompiledRule::new).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        for (CompiledRule rule : rules) {
            if (!rule.matches(request.getMethod(), path)) {
                continue;
            }
            String caller = rule.limit().key() == GatewayProperties.Key.USER
                    && request.getAttribute(TokenCheckFilter.SUBJECT_ATTRIBUTE) instanceof String subject
                    ? "user:" + subject : "ip:" + request.getRemoteAddr();
            Bucket bucket = buckets.computeIfAbsent(rule.limit().name() + "|" + caller,
                    k -> new Bucket(rule.limit().capacity(), rule.limit().refillPerMinute(), nanoTime.getAsLong()));
            long waitSeconds = bucket.tryTake(nanoTime.getAsLong());
            if (waitSeconds > 0) {
                response.setHeader("Retry-After", Long.toString(waitSeconds));
                ProblemResponses.write(response, ProblemCode.RATE_LIMITED,
                        "Too many requests. Please wait " + waitSeconds + " seconds and try again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Drops buckets that have refilled completely and been idle, so memory stays bounded. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void evictIdleBuckets() {
        long now = nanoTime.getAsLong();
        buckets.values().removeIf(b -> b.idleAndFull(now, TimeUnit.MINUTES.toNanos(10)));
    }

    int bucketCount() {
        return buckets.size();
    }

    private record CompiledRule(GatewayProperties.RateLimit limit, List<PathPattern> patterns) {

        CompiledRule(GatewayProperties.RateLimit limit) {
            this(limit, limit.paths().stream().map(PathPatternParser.defaultInstance::parse).toList());
        }

        boolean matches(String method, PathContainer path) {
            return (limit.methods().isEmpty() || limit.methods().stream().anyMatch(m -> m.equalsIgnoreCase(method)))
                    && patterns.stream().anyMatch(p -> p.matches(path));
        }
    }

    static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;
        private long lastUsed;

        Bucket(int capacity, int refillPerMinute, long now) {
            this.capacity = capacity;
            this.refillPerNano = refillPerMinute / (double) TimeUnit.MINUTES.toNanos(1);
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastUsed = now;
        }

        /** Takes one token; returns 0 if allowed, otherwise the whole seconds until one is available. */
        synchronized long tryTake(long now) {
            refill(now);
            lastUsed = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double nanosNeeded = (1 - tokens) / refillPerNano;
            return Math.max(1, (long) Math.ceil(nanosNeeded / TimeUnit.SECONDS.toNanos(1)));
        }

        synchronized boolean idleAndFull(long now, long idleNanos) {
            refill(now);
            return tokens >= capacity && now - lastUsed >= idleNanos;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefill = now;
        }
    }
}
