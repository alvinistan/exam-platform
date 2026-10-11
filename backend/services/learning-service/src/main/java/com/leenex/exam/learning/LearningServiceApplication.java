package com.leenex.exam.learning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * learning-service: topic mastery, misconceptions, learning goals and next-session recommendations.
 *
 * <p>Scans {@code com.leenex.exam.platform} for the shared error model, correlation IDs and security baseline.
 */
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.learning", "com.leenex.exam.platform"})
public class LearningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearningServiceApplication.class, args);
    }
}
