package com.leenex.exam.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * assessment-service: attempts, server-side timing, answer submission and authoritative scoring.
 *
 * <p>Scans {@code com.leenex.exam.platform} for the shared error model, correlation IDs and security baseline.
 */
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.assessment", "com.leenex.exam.platform"})
public class AssessmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssessmentServiceApplication.class, args);
    }
}
