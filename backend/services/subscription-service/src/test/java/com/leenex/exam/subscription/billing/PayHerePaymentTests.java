package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leenex.exam.platform.security.TestTokens;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The PayHere checkout form and notify_url: signatures, repeats, wrong amounts and charge-backs. */
class PayHerePaymentTests extends PaymentTestSupport {

    static final String MERCHANT = "1211149";
    static final String SECRET = "test-merchant-secret";

    @DynamicPropertySource
    static void payhere(DynamicPropertyRegistry registry) {
        registry.add("subscription.payments.provider", () -> "PAYHERE");
        registry.add("subscription.payments.payhere.merchant-id", () -> MERCHANT);
        registry.add("subscription.payments.payhere.merchant-secret", () -> SECRET);
        registry.add("subscription.payments.payhere.sandbox", () -> "true");
    }

    final UUID studentId = UUID.randomUUID();
    final String student = TestTokens.student(studentId);

    /** Posts a notification the way PayHere does, signed with {@code secret}. */
    MockHttpServletResponse notify(String orderId, String amount, String currency, String status, String paymentId,
                                   String secret) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("merchant_id", MERCHANT);
        p.put("order_id", orderId);
        p.put("payment_id", paymentId);
        p.put("payhere_amount", amount);
        p.put("payhere_currency", currency);
        p.put("status_code", status);
        p.put("md5sig", PayHereSigner.notificationSignature(MERCHANT, orderId, amount, currency, status, secret));
        p.put("method", "VISA");
        p.put("status_message", "Successfully completed the payment.");
        MockHttpServletRequestBuilder req = post("/api/payments/webhooks/payhere")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        p.forEach(req::param);
        return mvc.perform(req).andReturn().getResponse();
    }

    String startCheckout() throws Exception {
        String planId = plan("one-month", 1, 450000, "LKR", true);
        return read(expect(checkout(student, planId, "payhere-0001"), 201), "$.transactionId");
    }

    @Test
    void checkoutReturnsASignedPayHereForm() throws Exception {
        String planId = plan("one-month", 1, 450000, "LKR", true);
        MockHttpServletResponse r = expect(checkout(student, planId, "payhere-form"), 201);
        String tx = read(r, "$.transactionId");
        assertThat((String) read(r, "$.provider")).isEqualTo("PAYHERE");
        assertThat((String) read(r, "$.method")).isEqualTo("POST");
        assertThat((String) read(r, "$.action")).isEqualTo("https://sandbox.payhere.lk/pay/checkout");
        assertThat((String) read(r, "$.fields.merchant_id")).isEqualTo(MERCHANT);
        assertThat((String) read(r, "$.fields.order_id")).isEqualTo(tx);
        assertThat((String) read(r, "$.fields.amount")).isEqualTo("4500.00");
        assertThat((String) read(r, "$.fields.currency")).isEqualTo("LKR");
        assertThat((String) read(r, "$.fields.items")).isEqualTo("1 months");
        assertThat((String) read(r, "$.fields.notify_url")).isEqualTo("https://exams.example.lk/api/payments/webhooks/payhere");
        assertThat((String) read(r, "$.fields.return_url")).isEqualTo("https://exams.example.lk/checkout/return?tx=" + tx);
        assertThat((String) read(r, "$.fields.email")).isEqualTo("nimal@example.lk");
        assertThat((String) read(r, "$.fields.hash"))
                .isEqualTo(PayHereSigner.checkoutHash(MERCHANT, tx, "4500.00", "LKR", SECRET));
        // The secret itself never appears in the response.
        assertThat(r.getContentAsString()).doesNotContain(SECRET);

        assertThat((String) read(expect(call(get("/api/plans"), null, null), 200), "$.provider")).isEqualTo("PAYHERE");
    }

    @Test
    void payHereNeedsTheBuyerDetails() throws Exception {
        String planId = plan("no-details", 1, 450000, "LKR", true);
        MockHttpServletResponse r = expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", "payhere-nodetails"),
                student, Map.of("planId", planId)), 400);
        assertThat((String) read(r, "$.errors[0].field")).isEqualTo("customer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transaction", Integer.class)).isZero();
    }

    @Test
    void aGenuineNotificationActivatesPremiumAndARepeatIsIgnored() throws Exception {
        String tx = startCheckout();
        MockHttpServletResponse first = expect(notify(tx, "4500.00", "LKR", "2", "320027150501", SECRET), 200);
        assertThat(first.getContentAsString()).isEqualTo("ACTIVATED");
        assertThat(expect(notify(tx, "4500.00", "LKR", "2", "320027150501", SECRET), 200).getContentAsString())
                .isEqualTo("DUPLICATE");
        assertThat(txStatus(student, tx)).isEqualTo("SUCCEEDED");
        assertThat(premium(student)).isTrue();
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payment_transaction", String.class)).isEqualTo("320027150501");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription", Integer.class)).isEqualTo(1);
    }

    @Test
    void aForgedNotificationIsRejected() throws Exception {
        String tx = startCheckout();
        expect(notify(tx, "4500.00", "LKR", "2", "1", "wrong-secret"), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_event", Integer.class)).isZero();
        assertThat(txStatus(student, tx)).isEqualTo("PENDING");
        assertThat(premium(student)).isFalse();
    }

    @Test
    void aWrongAmountOrCurrencyActivatesNothing() throws Exception {
        String tx = startCheckout();
        assertThat(expect(notify(tx, "1.00", "LKR", "2", "11", SECRET), 200).getContentAsString()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(expect(notify(tx, "4500.00", "USD", "2", "12", SECRET), 200).getContentAsString()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(txStatus(student, tx)).isEqualTo("PENDING");
        assertThat(premium(student)).isFalse();
        // An order this service never created.
        assertThat(expect(notify(UUID.randomUUID().toString(), "4500.00", "LKR", "2", "13", SECRET), 200)
                .getContentAsString()).isEqualTo("UNKNOWN_ORDER");
    }

    @Test
    void aChargeBackRemovesPremium() throws Exception {
        String tx = startCheckout();
        expect(notify(tx, "4500.00", "LKR", "2", "21", SECRET), 200);
        assertThat(premium(student)).isTrue();
        assertThat(expect(notify(tx, "4500.00", "LKR", "-3", "21", SECRET), 200).getContentAsString()).isEqualTo("CHARGED_BACK");
        assertThat(premium(student)).isFalse();
        assertThat(txStatus(student, tx)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM subscription", String.class)).isEqualTo("CANCELLED");
        // A success replayed after the charge-back doesn't bring Premium back.
        assertThat(expect(notify(tx, "4500.00", "LKR", "2", "22", SECRET), 200).getContentAsString()).isEqualTo("IGNORED");
        assertThat(premium(student)).isFalse();
    }

    @Test
    void pendingThenFailedLeavesNoPremiumAndTheTestPageIsOff() throws Exception {
        String tx = startCheckout();
        assertThat(expect(notify(tx, "4500.00", "LKR", "0", "31", SECRET), 200).getContentAsString()).isEqualTo("PENDING");
        assertThat(expect(notify(tx, "4500.00", "LKR", "-2", "31", SECRET), 200).getContentAsString()).isEqualTo("FAILED");
        assertThat(txStatus(student, tx)).isEqualTo("FAILED");
        expect(call(post("/api/payments/test/" + tx), student, Map.of("outcome", "SUCCEEDED")), 404);
        assertThat(premium(student)).isFalse();
    }
}
