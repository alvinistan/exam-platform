package com.leenex.exam.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Public entry point for the web app and, later, the mobile apps (ADR-001).
 *
 * <p>Routes are declared in {@code application.yml} by path prefix, one block per service. Anything not listed,
 * including every {@code /internal/**} path, gets a 404 problem response. The gateway does not replace
 * authorization: it rejects forged or expired tokens early and rate-limits sensitive routes, but each service
 * still validates the token and decides access itself (ADR-004). Only the {@code platform.web} package is scanned;
 * the gateway has no Spring Security filter chain of its own.
 */
@EnableScheduling
@ConfigurationPropertiesScan("com.leenex.exam.gateway")
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.gateway", "com.leenex.exam.platform.web"})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
