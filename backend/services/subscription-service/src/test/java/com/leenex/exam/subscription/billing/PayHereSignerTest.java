package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayHereSignerTest {

    @Test
    void theHashIsAnUpperCaseMd5OfTheFieldsAndTheHashedSecret() {
        String inner = PayHereSigner.md5("secret");
        assertThat(inner).matches("[0-9A-F]{32}");
        assertThat(PayHereSigner.checkoutHash("1211149", "order-1", "1000.00", "LKR", "secret"))
                .isEqualTo(PayHereSigner.md5("1211149order-11000.00LKR" + inner));
        // The well-known MD5 of the empty string, to pin the encoding.
        assertThat(PayHereSigner.md5("")).isEqualTo("D41D8CD98F00B204E9800998ECF8427E");
    }

    @Test
    void theNotificationSignatureIncludesTheStatusCode() {
        String success = PayHereSigner.notificationSignature("m", "o", "10.00", "LKR", "2", "s");
        String failed = PayHereSigner.notificationSignature("m", "o", "10.00", "LKR", "-2", "s");
        assertThat(success).isNotEqualTo(failed);
        assertThat(PayHereSigner.matches(success, success.toLowerCase())).isTrue();
        assertThat(PayHereSigner.matches(success, failed)).isFalse();
        assertThat(PayHereSigner.matches(success, null)).isFalse();
    }

    @Test
    void payHereStatusCodesMapToOutcomes() {
        assertThat(Notification.Outcome.fromPayHere("2")).isEqualTo(Notification.Outcome.SUCCEEDED);
        assertThat(Notification.Outcome.fromPayHere("0")).isEqualTo(Notification.Outcome.PENDING);
        assertThat(Notification.Outcome.fromPayHere("-1")).isEqualTo(Notification.Outcome.CANCELLED);
        assertThat(Notification.Outcome.fromPayHere("-2")).isEqualTo(Notification.Outcome.FAILED);
        assertThat(Notification.Outcome.fromPayHere("-3")).isEqualTo(Notification.Outcome.CHARGED_BACK);
        assertThat(Notification.Outcome.fromPayHere("7")).isNull();
        assertThat(Notification.Outcome.fromPayHere(null)).isNull();
    }
}
