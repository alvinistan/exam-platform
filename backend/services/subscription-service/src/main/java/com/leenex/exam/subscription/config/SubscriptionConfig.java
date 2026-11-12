package com.leenex.exam.subscription.config;

import com.leenex.exam.platform.security.ApiAuthorizationRules;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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

    /** Plans on sale and open offers are public; PayHere's servers post payment results without a token (the signature is checked). */
    @Bean
    ApiAuthorizationRules publicBilling() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/api/plans", "/api/plans/offers").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/payhere").permitAll();
    }

    /**
     * Refuses to start with payment settings that would be unsafe: the TEST stand-in under the "prod" profile, PayHere
     * without merchant credentials, the PayHere sandbox under "prod" (test cards would buy real access), or live
     * PayHere without HTTPS (ADR-011).
     */
    @Bean
    ApplicationRunner paymentSettingsCheck(SubscriptionProperties properties, Environment environment) {
        SubscriptionProperties.Payments p = properties.payments();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (!p.test() && !"PAYHERE".equalsIgnoreCase(p.provider())) {
            throw new IllegalStateException("subscription.payments.provider must be PAYHERE or TEST");
        }
        if (p.test() && prod) {
            throw new IllegalStateException("The TEST payment provider is not allowed with the prod profile");
        }
        if (!p.test()) {
            if (isBlank(p.payhere().merchantId()) || isBlank(p.payhere().merchantSecret())) {
                throw new IllegalStateException("PAYHERE needs PAYHERE_MERCHANT_ID and PAYHERE_MERCHANT_SECRET");
            }
            if (p.payhere().sandbox() && prod && !p.allowSandboxInProd()) {
                throw new IllegalStateException("The PayHere sandbox is not allowed with the prod profile (set "
                        + "PAYHERE_SANDBOX=false, or PAYMENTS_ALLOW_SANDBOX_IN_PROD=true for a staging site)");
            }
            if (!p.payhere().sandbox() && !"https".equals(p.publicBaseUrl().getScheme())) {
                throw new IllegalStateException("Live PayHere needs an https PUBLIC_BASE_URL");
            }
        }
        return args -> {
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
