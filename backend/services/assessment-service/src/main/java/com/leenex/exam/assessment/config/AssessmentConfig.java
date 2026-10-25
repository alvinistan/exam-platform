package com.leenex.exam.assessment.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AssessmentProperties.class)
public class AssessmentConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
