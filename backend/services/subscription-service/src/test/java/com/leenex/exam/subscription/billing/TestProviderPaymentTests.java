package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.leenex.exam.platform.security.TestTokens;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plans, checkout, activation, renewal and expiry with the labelled TEST provider (the default). */
class TestProviderPaymentTests extends PaymentTestSupport {

    final UUID studentId = UUID.randomUUID();
    final String student = TestTokens.student(studentId);

    MockHttpServletResponse pay(String token, String txId, String outcome) throws Exception {
        return call(post("/api/payments/test/" + txId), token, Map.of("outcome", outcome));
    }

    @Test
    void administratorsManagePlansAndStudentsSeeOnlyThoseOnSale() throws Exception {
        String month = plan("one-month", 1, 150000, "LKR", true);
        plan("hidden", 12, 1200000, "LKR", false);

        MockHttpServletResponse publicPlans = expect(call(get("/api/plans"), null, null), 200);
        assertThat((String) read(publicPlans, "$.provider")).isEqualTo("TEST");
        assertThat((Boolean) read(publicPlans, "$.testPayments")).isTrue();
        assertThat((List<String>) read(publicPlans, "$.plans[*].id")).containsExactly(month);
        assertThat((Integer) read(publicPlans, "$.plans[0].priceLkrMinor")).isEqualTo(150000);
        assertThat((Object) read(publicPlans, "$.plans[0].priceUsdMinor")).isNull();
        assertThat((String) read(publicPlans, "$.plans[0].scopeType")).isEqualTo("ALL");

        Map<String, Object> change = planBody(null, 1, 175000L, 600L, "ALL", List.of(), null, null, null, true);
        change.put("name", "Monthly");
        expect(call(put("/api/admin/billing/plans/" + month), ADMIN, change), 400); // no If-Match
        expect(call(put("/api/admin/billing/plans/" + month).header("If-Match", "\"0\""), ADMIN, change), 200);
        expect(call(put("/api/admin/billing/plans/" + month).header("If-Match", "\"0\""), ADMIN, change), 409);
        MockHttpServletResponse changed = expect(call(get("/api/plans"), null, null), 200);
        assertThat((Integer) read(changed, "$.plans[0].priceLkrMinor")).isEqualTo(175000);
        assertThat((Integer) read(changed, "$.plans[0].priceUsdMinor")).isEqualTo(600);

        Map<String, Object> bad = new java.util.HashMap<>(change);
        bad.put("code", "two-months");
        bad.put("durationMonths", 2);
        expect(call(post("/api/admin/billing/plans"), ADMIN, bad), 400);
        bad.put("durationMonths", 3);
        bad.put("priceLkrMinor", null);
        bad.put("priceUsdMinor", null);
        expect(call(post("/api/admin/billing/plans"), ADMIN, bad), 400); // no price
        bad.put("priceUsdMinor", 1500);
        bad.put("practiceLimit", 0);
        bad.put("mockLimit", 0);
        bad.put("adaptiveLimit", 0);
        expect(call(post("/api/admin/billing/plans"), ADMIN, bad), 400); // nothing included
        bad.put("practiceLimit", null);
        bad.put("code", "one-month");
        expect(call(post("/api/admin/billing/plans"), ADMIN, bad), 400); // code taken
        expect(call(post("/api/admin/billing/plans"), student, bad), 403);
        expect(call(get("/api/admin/billing/plans"), student, null), 403);
        assertThat((List<?>) read(expect(call(get("/api/admin/billing/plans"), ADMIN, null), 200), "$")).hasSize(2);
    }

    @Test
    void premiumStartsOnlyAfterTheConfirmedPaymentAndOnlyOnce() throws Exception {
        String planId = plan("one-month", 1, 150000, "LKR", true);
        MockHttpServletResponse first = expect(checkout(student, planId, "key-0000001"), 201);
        String tx = read(first, "$.transactionId");
        assertThat((String) read(first, "$.provider")).isEqualTo("TEST");
        assertThat((String) read(first, "$.action")).isEqualTo("https://exams.example.lk/checkout/test/" + tx);
        // The same key (a double click or a retry) returns the same payment.
        assertThat((String) read(expect(checkout(student, planId, "key-0000001"), 201), "$.transactionId")).isEqualTo(tx);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transaction", Integer.class)).isEqualTo(1);

        assertThat(txStatus(student, tx)).isEqualTo("PENDING");
        assertThat(premium(student)).isFalse();

        // Someone else can neither see nor confirm this payment.
        String other = TestTokens.student(UUID.randomUUID());
        expect(call(get("/api/subscriptions/checkout/" + tx), other, null), 404);
        expect(pay(other, tx, "SUCCEEDED"), 404);

        assertThat((String) read(expect(pay(student, tx, "SUCCEEDED"), 200), "$.result")).isEqualTo("ACTIVATED");
        assertThat((String) read(expect(pay(student, tx, "SUCCEEDED"), 200), "$.result")).isEqualTo("DUPLICATE");
        assertThat(txStatus(student, tx)).isEqualTo("SUCCEEDED");
        assertThat(premium(student)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entitlement WHERE student_id = ?", Integer.class, studentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'SubscriptionActivated'",
                Integer.class)).isEqualTo(1);

        MockHttpServletResponse mine = expect(call(get("/api/subscriptions/me"), student, null), 200);
        assertThat((Boolean) read(mine, "$.premium")).isTrue();
        OffsetDateTime ends = OffsetDateTime.parse(read(mine, "$.premiumEndsAt"));
        assertThat(ends).isBetween(OffsetDateTime.now().plusDays(27), OffsetDateTime.now().plusDays(32));
        assertThat((List<String>) read(mine, "$.periods[*].planName")).containsExactly("1 months");
        assertThat((List<String>) read(mine, "$.payments[*].status")).containsExactly("SUCCEEDED");

        // A cancellation arriving after success changes nothing.
        assertThat((String) read(expect(pay(student, tx, "CANCELLED"), 200), "$.result")).isEqualTo("IGNORED");
        assertThat(premium(student)).isTrue();
    }

    @Test
    void aSecondPlanStartsStraightAwayAlongsideTheFirst() throws Exception {
        String planId = plan("three-months", 3, 400000, "LKR", true);
        String first = read(expect(checkout(student, planId, "renew-00001"), 201), "$.transactionId");
        expect(pay(student, first, "SUCCEEDED"), 200);
        String second = read(expect(checkout(student, planId, "renew-00002"), 201), "$.transactionId");
        expect(pay(student, second, "SUCCEEDED"), 200);

        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT starts_at, ends_at FROM subscription WHERE student_id = ? ORDER BY starts_at", studentId);
        assertThat(periods).hasSize(2);
        assertThat(((java.sql.Timestamp) periods.get(1).get("starts_at")).toInstant())
                .isBefore(((java.sql.Timestamp) periods.get(0).get("ends_at")).toInstant());
        MockHttpServletResponse mine = expect(call(get("/api/subscriptions/me"), student, null), 200);
        OffsetDateTime ends = OffsetDateTime.parse(read(mine, "$.premiumEndsAt"));
        assertThat(ends).isBetween(OffsetDateTime.now().plusMonths(3).minusDays(2), OffsetDateTime.now().plusMonths(3).plusDays(2));
        assertThat((List<?>) read(mine, "$.plans")).hasSize(2);
    }

    @Test
    void failedOrCancelledPaymentsGiveNoPremium() throws Exception {
        String planId = plan("one-month", 1, 150000, "LKR", true);
        String failed = read(expect(checkout(student, planId, "fail-000001"), 201), "$.transactionId");
        assertThat((String) read(expect(pay(student, failed, "FAILED"), 200), "$.result")).isEqualTo("FAILED");
        String cancelled = read(expect(checkout(student, planId, "cancel-0001"), 201), "$.transactionId");
        assertThat((String) read(expect(pay(student, cancelled, "CANCELLED"), 200), "$.result")).isEqualTo("CANCELLED");

        assertThat(txStatus(student, failed)).isEqualTo("FAILED");
        assertThat(txStatus(student, cancelled)).isEqualTo("CANCELLED");
        assertThat(premium(student)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription", Integer.class)).isZero();
    }

    @Test
    void checkoutRequestsAreValidated() throws Exception {
        String planId = plan("one-month", 1, 150000, "LKR", true);
        String hidden = plan("hidden", 6, 700000, "LKR", false);
        expect(checkout(null, planId, "anon-000001"), 401);
        expect(checkout(student, planId, null), 400);
        expect(checkout(student, planId, "short"), 400);
        expect(checkout(student, hidden, "hidden-0001"), 404);
        expect(checkout(student, UUID.randomUUID().toString(), "nothing-001"), 404);
        expect(checkout(student, planId, "same-key-01"), 201);
        expect(checkout(student, hidden, "same-key-01"), 409);

        Map<String, Object> c = customer();
        c.put("email", "not-an-email");
        expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", "bad-email-1"), student,
                Map.of("planId", planId, "customer", c)), 400);
    }

    @Test
    void theSweepExpiresFinishedPeriodsAndAbandonedCheckouts() throws Exception {
        String planId = plan("one-month", 1, 150000, "LKR", true);
        String paid = read(expect(checkout(student, planId, "sweep-00001"), 201), "$.transactionId");
        expect(pay(student, paid, "SUCCEEDED"), 200);
        String abandoned = read(expect(checkout(student, planId, "sweep-00002"), 201), "$.transactionId");

        OffsetDateTime past = OffsetDateTime.now().minusDays(2).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE subscription SET starts_at = ?, ends_at = ?", past.minusMonths(1), past);
        jdbc.update("UPDATE entitlement SET starts_at = ?, ends_at = ?", past.minusMonths(1), past);
        jdbc.update("UPDATE payment_transaction SET created_at = ? WHERE id = ?", past, UUID.fromString(abandoned));

        assertThat(premium(student)).isFalse(); // access ends at ends_at, sweep or not
        service.sweep();
        assertThat(jdbc.queryForObject("SELECT status FROM subscription", String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'SubscriptionExpired'",
                Integer.class)).isEqualTo(1);
        assertThat(txStatus(student, abandoned)).isEqualTo("FAILED");

        // A payment that completes late still counts: the money was taken.
        assertThat((String) read(expect(pay(student, abandoned, "SUCCEEDED"), 200), "$.result")).isEqualTo("ACTIVATED");
        assertThat(premium(student)).isTrue();
    }
}
