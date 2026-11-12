package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leenex.exam.platform.security.TestTokens;
import com.leenex.exam.subscription.finance.RefundGateway;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Phase 13: refunds (manual, through PayHere's API, and pending ones settled by hand), payment search and the
 * finance summary. PayHere's Refund API is replaced by a mock; without an API key refunds are recorded manually.
 */
class FinanceTests extends PaymentTestSupport {

    @MockitoBean
    RefundGateway gateway;

    record Student(UUID id, String token) {
        static Student next() {
            UUID id = UUID.randomUUID();
            return new Student(id, TestTokens.student(id));
        }
    }

    /** Buys a plan with the TEST provider and confirms it; returns the payment id. */
    String buy(Student s, String planId) throws Exception {
        MockHttpServletResponse r = expect(call(post("/api/subscriptions/checkout")
                .header("Idempotency-Key", "buy-" + UUID.randomUUID()), s.token(), Map.of("planId", planId,
                "currency", "LKR", "customer", customer())), 201);
        String tx = read(r, "$.transactionId");
        expect(call(post("/api/payments/test/" + tx), s.token(), Map.of("outcome", "SUCCEEDED")), 200);
        return tx;
    }

    MockHttpServletResponse refund(String tx, Long amount, boolean stopAccess, String method) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountMinor", amount);
        body.put("reason", "Asked for a refund within 7 days");
        body.put("stopAccess", stopAccess);
        body.put("method", method);
        body.put("reference", method == null || "MANUAL".equals(method) ? "PayHere portal 5600" : null);
        return call(post("/api/admin/billing/payments/" + tx + "/refunds"), ADMIN, body);
    }

    MockHttpServletResponse detail(String tx) throws Exception {
        return expect(call(get("/api/admin/billing/payments/" + tx), ADMIN, null), 200);
    }

    @Test
    void aPaymentIsRefundedInPartsAndTheLastPartStopsThePlan() throws Exception {
        String planId = plan("month", 1, 150000, "LKR", true);
        Student s = Student.next();
        String tx = buy(s, planId);
        MockHttpServletResponse d = detail(tx);
        assertThat(((Number) read(d, "$.refundableMinor")).longValue()).isEqualTo(150000L);
        assertThat((Boolean) read(d, "$.apiRefunds")).isFalse();

        MockHttpServletResponse part = expect(refund(tx, 50000L, false, null), 201);
        assertThat((String) read(part, "$.method")).isEqualTo("MANUAL");
        assertThat((String) read(part, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) read(part, "$.providerRef")).isEqualTo("PayHere portal 5600");
        assertThat(premium(s.token())).isTrue();
        assertThat((String) read(detail(tx), "$.payment.refundState")).isEqualTo("PARTIAL");

        MockHttpServletResponse tooMuch = expect(refund(tx, 100001L, true, "MANUAL"), 400);
        assertThat((String) read(tooMuch, "$.errors[0].field")).isEqualTo("amountMinor");
        assertThat((String) read(tooMuch, "$.errors[0].message")).isEqualTo("refund between LKR 0.01 and LKR 1,000.00");

        // The rest, stopping the plan.
        expect(refund(tx, null, true, "MANUAL"), 201);
        assertThat(premium(s.token())).isFalse();
        MockHttpServletResponse after = detail(tx);
        assertThat((String) read(after, "$.payment.refundState")).isEqualTo("FULL");
        assertThat((String) read(after, "$.payment.periodStatus")).isEqualTo("CANCELLED");
        assertThat(((Number) read(after, "$.refundableMinor")).longValue()).isZero();
        assertThat((List<Integer>) read(after, "$.refunds[*].amountMinor")).containsExactly(50000, 100000);
        expect(refund(tx, null, false, "MANUAL"), 409);

        // The student sees what came back, without the administrator's reasons.
        MockHttpServletResponse mine = expect(call(get("/api/subscriptions/payments"), s.token(), null), 200);
        assertThat(((Number) read(mine, "$[0].refundedMinor")).longValue()).isEqualTo(150000L);
        assertThat((List<Integer>) read(mine, "$[0].refunds[*].amountMinor")).containsExactly(50000, 100000);
        assertThat(mine.getContentAsString()).doesNotContain("within 7 days");
        // The audit log is append-only and shared by every test, so only this payment's entries are checked.
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE action LIKE '%REFUND%' "
                + "AND summary->>'transactionId' = ?", String.class, tx))
                .containsExactlyInAnyOrder("REFUND_RECORDED", "REFUND_RECORDED", "SUBSCRIPTION_REFUNDED");
        verify(gateway, never()).refund(any(), any(), any());
    }

    @Test
    void withAnApiKeyRefundsGoThroughPayHere() throws Exception {
        String planId = plan("month", 1, 150000, "LKR", true);
        Student s = Student.next();
        String full = buy(s, planId);
        String part = buy(s, planId);
        jdbc.update("UPDATE payment_transaction SET provider = 'PAYHERE', provider_ref = 'PH-' || right(id::text, 12)");
        String fullRef = "PH-" + full.substring(full.length() - 12);
        String partRef = "PH-" + part.substring(part.length() - 12);
        when(gateway.available()).thenReturn(true);
        when(gateway.refund(fullRef, null, "Asked for a refund within 7 days"))
                .thenReturn(new RefundGateway.Result(true, "560034010257", "Successfully processed the refund"));
        when(gateway.refund(partRef, "500.00", "Asked for a refund within 7 days"))
                .thenReturn(new RefundGateway.Result(false, null, "PayHere: Error processing refund"));
        assertThat((Boolean) read(detail(full), "$.apiRefunds")).isTrue();

        // The whole payment: PayHere is asked without an amount.
        MockHttpServletResponse ok = expect(refund(full, null, true, null), 201);
        assertThat((String) read(ok, "$.method")).isEqualTo("PAYHERE_API");
        assertThat((String) read(ok, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) read(ok, "$.providerRef")).isEqualTo("560034010257");

        // PayHere refuses: nothing is refunded, and the amount can be refunded again.
        MockHttpServletResponse refused = expect(refund(part, 50000L, false, null), 201);
        assertThat((String) read(refused, "$.status")).isEqualTo("FAILED");
        assertThat((String) read(refused, "$.failureReason")).isEqualTo("PayHere: Error processing refund");
        assertThat(((Number) read(detail(part), "$.refundableMinor")).longValue()).isEqualTo(150000L);
        assertThat((String) read(detail(part), "$.payment.refundState")).isEqualTo("NONE");

        // The administrator can still choose to record one made in PayHere's portal.
        assertThat((String) read(expect(refund(part, 50000L, false, "MANUAL"), 201), "$.method")).isEqualTo("MANUAL");
    }

    @Test
    void aPendingRefundIsSettledByHandAndStillCountsMeanwhile() throws Exception {
        String planId = plan("month", 1, 150000, "LKR", true);
        Student s = Student.next();
        String tx = buy(s, planId);
        UUID pending = UUID.randomUUID();
        jdbc.update("INSERT INTO refund (id, transaction_id, amount_minor, currency, reason, method, status, stop_access, "
                + "created_by) VALUES (?, ?::uuid, 150000, 'LKR', 'Service stopped mid-way', 'PAYHERE_API', 'PENDING', "
                + "true, ?)", pending, tx, UUID.randomUUID());
        assertThat(((Number) read(detail(tx), "$.refundableMinor")).longValue()).isZero();
        expect(refund(tx, 1000L, false, "MANUAL"), 409);
        assertThat(premium(s.token())).isTrue();

        MockHttpServletResponse settled = expect(call(post("/api/admin/billing/refunds/" + pending + "/resolve"), ADMIN,
                Map.of("outcome", "SUCCEEDED", "reference", "560034010999")), 200);
        assertThat((String) read(settled, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) read(settled, "$.providerRef")).isEqualTo("560034010999");
        assertThat(premium(s.token())).isFalse();
        expect(call(post("/api/admin/billing/refunds/" + pending + "/resolve"), ADMIN, Map.of("outcome", "FAILED")), 409);
    }

    @Test
    void onlyPaidPaymentsCanBeRefundedAndOnlyByAdministrators() throws Exception {
        String planId = plan("month", 1, 150000, "LKR", true);
        Student s = Student.next();
        String pending = read(expect(checkout(s.token(), planId, "pending-0001"), 201), "$.transactionId");
        expect(refund(pending, null, false, "MANUAL"), 409);
        String tx = buy(s, planId);
        expect(call(post("/api/admin/billing/payments/" + tx + "/refunds"), s.token(), Map.of("reason", "Mine",
                "stopAccess", false)), 403);
        expect(call(get("/api/admin/billing/finance/summary"), s.token(), null), 403);
        // PayHere isn't set up: asking for its API is refused.
        assertThat((String) read(expect(refund(tx, null, false, "PAYHERE_API"), 400), "$.errors[0].field"))
                .isEqualTo("method");
        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("stopAccess", false);
        expect(call(post("/api/admin/billing/payments/" + tx + "/refunds"), ADMIN, noReason), 400);
        expect(call(get("/api/admin/billing/payments/" + UUID.randomUUID()), ADMIN, null), 404);
    }

    @Test
    void paymentsCanBeSearchedAndSummarised() throws Exception {
        String lkr = plan("month", 1, 150000, "LKR", true);
        String usd = plan("year", 12, 4500, "USD", true);
        Student a = Student.next();
        Student b = Student.next();
        String paidLkr = buy(a, lkr);
        MockHttpServletResponse usdCheckout = expect(call(post("/api/subscriptions/checkout")
                .header("Idempotency-Key", "usd-0000001"), b.token(), Map.of("planId", usd, "currency", "USD",
                "customer", customer())), 201);
        String paidUsd = read(usdCheckout, "$.transactionId");
        expect(call(post("/api/payments/test/" + paidUsd), b.token(), Map.of("outcome", "SUCCEEDED")), 200);
        String failed = read(expect(checkout(b.token(), lkr, "failed-00001"), 201), "$.transactionId");
        expect(call(post("/api/payments/test/" + failed), b.token(), Map.of("outcome", "FAILED")), 200);
        expect(refund(paidLkr, 30000L, false, "MANUAL"), 201);

        MockHttpServletResponse all = expect(call(get("/api/admin/billing/payments?size=10"), ADMIN, null), 200);
        assertThat(((Number) read(all, "$.page.totalElements")).intValue()).isEqualTo(3);
        assertThat((List<String>) read(expect(call(get("/api/admin/billing/payments?status=PAID&currency=USD"), ADMIN,
                null), 200), "$.items[*].id")).containsExactly(paidUsd);
        assertThat((List<String>) read(expect(call(get("/api/admin/billing/payments?status=REFUNDED"), ADMIN, null),
                200), "$.items[*].id")).containsExactly(paidLkr);
        assertThat((List<String>) read(expect(call(get("/api/admin/billing/payments?status=FAILED&studentId=" + b.id()),
                ADMIN, null), 200), "$.items[*].id")).containsExactly(failed);
        assertThat((List<String>) read(expect(call(get("/api/admin/billing/payments?q=" + paidUsd), ADMIN, null), 200),
                "$.items[*].id")).containsExactly(paidUsd);
        expect(call(get("/api/admin/billing/payments?size=7"), ADMIN, null), 400);
        expect(call(get("/api/admin/billing/payments?status=LOST"), ADMIN, null), 400);
        expect(call(get("/api/admin/billing/finance/summary?tz=Mars/Base"), ADMIN, null), 400);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        expect(call(get("/api/admin/billing/finance/summary?from=" + today.minusDays(500) + "&to=" + today), ADMIN,
                null), 400);

        // A's plan ends in 5 days (months differ in length, so the test doesn't rely on "1 month").
        jdbc.update("UPDATE entitlement SET ends_at = now() + interval '5 days' WHERE student_id = ?", a.id());
        MockHttpServletResponse sum = expect(call(get("/api/admin/billing/finance/summary?tz=Asia/Colombo&from="
                + today.minusDays(1) + "&to=" + today.plusDays(1)), ADMIN, null), 200);
        assertThat((List<String>) read(sum, "$.money[*].currency")).containsExactly("LKR", "USD");
        assertThat(((Number) read(sum, "$.money[0].paidMinor")).longValue()).isEqualTo(150000L);
        assertThat(((Number) read(sum, "$.money[0].refundedMinor")).longValue()).isEqualTo(30000L);
        assertThat(((Number) read(sum, "$.money[0].netMinor")).longValue()).isEqualTo(120000L);
        assertThat(((Number) read(sum, "$.money[1].paidMinor")).longValue()).isEqualTo(4500L);
        assertThat((Integer) read(sum, "$.payments.paid")).isEqualTo(2);
        assertThat((Integer) read(sum, "$.payments.failed")).isEqualTo(1);
        assertThat((Integer) read(sum, "$.subscribers.activeNow")).isEqualTo(2);
        assertThat((Integer) read(sum, "$.subscribers.newBuyers")).isEqualTo(2);
        // A's plan ends within 30 days; B's 12-month plan doesn't.
        assertThat((Integer) read(sum, "$.subscribers.endingIn30Days")).isEqualTo(1);
        assertThat((List<String>) read(sum, "$.endingSoon[*].studentId")).containsExactly(a.id().toString());
        assertThat((List<String>) read(sum, "$.byPlan[*].planName")).containsExactly("1 months", "12 months");
        assertThat((List<String>) read(sum, "$.days[*].date")).contains(today.toString());
    }
}
