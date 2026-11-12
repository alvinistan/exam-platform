package com.leenex.exam.subscription.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The payment settings a server refuses to start with (ADR-011, Phase 14). */
class PaymentSettingsCheckTest {

    static SubscriptionProperties settings(String provider, boolean sandbox, String baseUrl, boolean allowSandbox) {
        return new SubscriptionProperties(null, null, new SubscriptionProperties.Payments(provider, URI.create(baseUrl),
                new SubscriptionProperties.PayHere("1234567", "merchant-secret", sandbox, null, null), allowSandbox));
    }

    static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    static void check(SubscriptionProperties p, MockEnvironment env) {
        new SubscriptionConfig().paymentSettingsCheck(p, env);
    }

    @Test
    void prodRefusesTheTestProvider() {
        assertThatThrownBy(() -> check(settings("TEST", true, "https://exams.example.lk", false), env("prod")))
                .hasMessageContaining("TEST payment provider");
    }

    @Test
    void prodRefusesThePayHereSandboxUnlessAllowedForStaging() {
        assertThatThrownBy(() -> check(settings("PAYHERE", true, "https://exams.example.lk", false), env("prod")))
                .hasMessageContaining("sandbox");
        assertThatCode(() -> check(settings("PAYHERE", true, "https://staging.example.lk", true), env("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void livePayHereNeedsHttps() {
        assertThatThrownBy(() -> check(settings("PAYHERE", false, "http://exams.example.lk", false), env("prod")))
                .hasMessageContaining("https");
        assertThatCode(() -> check(settings("PAYHERE", false, "https://exams.example.lk", false), env("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void localDevelopmentMayUseTheSandbox() {
        assertThatCode(() -> check(settings("PAYHERE", true, "http://localhost:8088", false), env("local")))
                .doesNotThrowAnyException();
    }
}
