package com.leenex.exam.subscription.config;

import com.leenex.exam.platform.security.ApiAuthorizationRules;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SubscriptionProperties.class)
public class SubscriptionConfig {

    /** Anyone browsing the catalogue may ask which exams are free; signed-in callers also get their own status. */
    @Bean
    ApiAuthorizationRules publicAccessStatus() {
        return rules -> rules.requestMatchers(HttpMethod.GET, "/api/access/exams").permitAll();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
