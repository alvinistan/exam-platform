package com.leenex.exam.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * identity-service: google sign-in, platform tokens, users, roles and account status.
 *
 * <p>Scans {@code com.leenex.exam.platform} for the shared error model, correlation IDs and security baseline.
 */
@EnableScheduling
@ConfigurationPropertiesScan("com.leenex.exam.identity")
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.identity", "com.leenex.exam.platform"})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
