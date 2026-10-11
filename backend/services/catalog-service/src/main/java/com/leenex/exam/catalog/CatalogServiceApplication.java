package com.leenex.exam.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * catalog-service: exams, categories, subjects, modules, topics, trial and main question banks, and media.
 *
 * <p>Scans {@code com.leenex.exam.platform} for the shared error model, correlation IDs and security baseline.
 */
@SpringBootApplication(scanBasePackages = {"com.leenex.exam.catalog", "com.leenex.exam.platform"})
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
