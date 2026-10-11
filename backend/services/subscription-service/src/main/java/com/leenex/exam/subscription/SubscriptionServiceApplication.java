package com.leenex.exam.subscription;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * subscription-service: free-access policies, access decisions, plans, payments, subscriptions and entitlements.
 *
 * <p>Scans {@code com.leenex.exam.platform} for the shared error model, correlation IDs and security baseline.
 */
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.subscription", "com.leenex.exam.platform"})
public class SubscriptionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionServiceApplication.class, args);
    }
}
