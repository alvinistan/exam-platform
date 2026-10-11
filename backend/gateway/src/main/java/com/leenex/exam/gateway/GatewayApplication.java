package com.leenex.exam.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Public entry point for the web app and, later, the mobile apps (ADR-001).
 *
 * <p>Routes are declared in {@code application.yml} by path prefix, one block per service. Anything not listed,
 * including every {@code /internal/**} path, gets a 404 problem response. The gateway does not replace
 * authorization: each service validates the access token itself (ADR-004). Only the {@code platform.web} package
 * is scanned; the gateway has no Spring Security baseline of its own.
 */
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.gateway", "com.leenex.exam.platform.web"})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
