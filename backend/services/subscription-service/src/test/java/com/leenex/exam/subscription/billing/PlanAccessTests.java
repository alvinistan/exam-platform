package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.leenex.exam.platform.security.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Phase 11B: plans for one exam, a fixed bundle or everything, with attempt limits per exam type, paid in LKR or USD
 * (labelled TEST provider). Limits are enforced by the same access decisions assessment-service asks for.
 */
class PlanAccessTests extends PaymentTestSupport {

    final UUID olevel = UUID.randomUUID();
    final UUID studentId = UUID.randomUUID();
    final String student = TestTokens.student(studentId);

    /** Buys a plan with the TEST provider and returns the transaction id. */
    String buy(String planId, String currency, String key) throws Exception {
        MockHttpServletResponse r = expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", key),
                student, Map.of("planId", planId, "currency", currency, "customer", customer())), 201);
        String tx = read(r, "$.transactionId");
        assertThat((String) read(expect(call(post("/api/payments/test/" + tx), student, Map.of("outcome", "SUCCEEDED")),
                200), "$.result")).isEqualTo("ACTIVATED");
        return tx;
    }

    String access(UUID exam, int modeIndex, String field) throws Exception {
        return String.valueOf((Object) read(expect(call(get("/api/access/exams").param("examIds", exam.toString()),
                student, null), 200), "$['" + exam + "'].modes[" + modeIndex + "]." + field));
    }

    @Test
    void anExamPlanCoversOnlyThatExamAndCountsEachExamTypeSeparately() throws Exception {
        UUID science = exam("Science", olevel);
        UUID maths = exam("Mathematics", olevel);
        // Practice unlimited, Mock 2 attempts, Adaptive not included.
        String planId = plan(planBody("science-1m", 1, 300000L, 1500L, "EXAM", List.of(science), null, 2, 0, true));

        MockHttpServletResponse forScience = expect(call(get("/api/plans").param("examId", science.toString()), null, null), 200);
        assertThat((List<String>) read(forScience, "$.plans[*].id")).containsExactly(planId);
        assertThat((List<String>) read(forScience, "$.plans[0].exams[*].title")).containsExactly("Science");
        assertThat((Integer) read(forScience, "$.plans[0].limits.mock")).isEqualTo(2);
        assertThat((Object) read(forScience, "$.plans[0].limits.practice")).isNull();
        assertThat((List<?>) read(expect(call(get("/api/plans").param("examId", maths.toString()), null, null), 200),
                "$.plans")).isEmpty();

        String tx = buy(planId, "USD", "science-usd-1");
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM payment_transaction WHERE id = ?::uuid", Long.class, tx))
                .isEqualTo(1500L);
        assertThat(jdbc.queryForObject("SELECT currency FROM payment_transaction WHERE id = ?::uuid", String.class, tx))
                .isEqualTo("USD");

        // Practice: unlimited on Science.
        assertThat(access(science, 0, "access")).isEqualTo("OPEN");
        for (int i = 0; i < 6; i++) {
            assertThat((String) read(start(studentId, science, "PRACTICE"), "$.reason")).isEqualTo("ACTIVE_ENTITLEMENT");
        }
        // Mock: 2 plan attempts, then the 1 free attempt, then locked.
        assertThat(access(science, 1, "access")).isEqualTo("PLAN");
        assertThat(access(science, 1, "planLeft")).isEqualTo("2");
        assertThat((String) read(start(studentId, science, "MOCK"), "$.reason")).isEqualTo("PLAN_ATTEMPT");
        assertThat((Integer) read(start(studentId, science, "MOCK"), "$.attemptsLeft")).isZero();
        assertThat((String) read(start(studentId, science, "MOCK"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        MockHttpServletResponse refused = start(studentId, science, "MOCK");
        assertThat((Boolean) read(refused, "$.allowed")).isFalse();
        assertThat((String) read(refused, "$.reason")).isEqualTo("PLAN_ATTEMPTS_USED");
        // Adaptive isn't in the plan: only the free attempts apply.
        assertThat(access(science, 2, "access")).isEqualTo("FREE");
        // Mathematics isn't covered at all.
        assertThat((Boolean) read(expect(call(get("/api/access/exams").param("examIds", maths.toString()), student, null),
                200), "$['" + maths + "'].premium")).isFalse();

        MockHttpServletResponse mine = expect(call(get("/api/subscriptions/me"), student, null), 200);
        assertThat((String) read(mine, "$.plans[0].scopeType")).isEqualTo("EXAM");
        assertThat((String) read(mine, "$.plans[0].exams[0].title")).isEqualTo("Science");
        assertThat((List<String>) read(mine, "$.plans[0].exams[0].modes[*].mode")).containsExactly("PRACTICE", "MOCK");
        assertThat((Integer) read(mine, "$.plans[0].exams[0].modes[0].used")).isEqualTo(6);
        assertThat((Object) read(mine, "$.plans[0].exams[0].modes[0].left")).isNull();
        assertThat((Integer) read(mine, "$.plans[0].exams[0].modes[1].used")).isEqualTo(2);
        assertThat((Integer) read(mine, "$.plans[0].exams[0].modes[1].left")).isZero();
    }

    @Test
    void aBundleCoversItsFixedListWithLimitsPerSubject() throws Exception {
        UUID science = exam("Science", olevel);
        UUID maths = exam("Mathematics", olevel);
        UUID history = exam("History", olevel);
        Map<String, Object> body = planBody("ol-bundle-1m", 1, 600000L, 2000L, "BUNDLE", List.of(science, maths),
                null, 1, null, true);
        body.put("categoryId", olevel.toString());
        String planId = plan(body);
        assertThat((List<String>) read(expect(call(get("/api/plans").param("categoryId", olevel.toString()), null, null),
                200), "$.plans[*].id")).containsExactly(planId);

        buy(planId, "LKR", "bundle-lkr-1");
        // One Mock attempt per subject.
        assertThat((String) read(start(studentId, science, "MOCK"), "$.reason")).isEqualTo("PLAN_ATTEMPT");
        assertThat((String) read(start(studentId, maths, "MOCK"), "$.reason")).isEqualTo("PLAN_ATTEMPT");
        assertThat((String) read(start(studentId, science, "MOCK"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        // A subject added to the category later isn't in the bundle that was bought (fixed list).
        assertThat((String) read(start(studentId, history, "PRACTICE"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        UUID english = exam("English", olevel);
        Map<String, Object> wider = planBody(null, 1, 600000L, 2000L, "BUNDLE", List.of(science, maths, english),
                null, 1, null, true);
        expect(call(put("/api/admin/billing/plans/" + planId).header("If-Match", "\"0\""), ADMIN, wider), 200);
        assertThat((String) read(start(studentId, english, "MOCK"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        // Same with the limits: the student keeps the copy they bought.
        assertThat(jdbc.queryForObject("SELECT limit_mock FROM entitlement WHERE student_id = ?", Integer.class, studentId))
                .isEqualTo(1);
    }

    @Test
    void theUnlimitedPlanIsUsedBeforeALimitedOneAndPlansStack() throws Exception {
        UUID science = exam("Science", olevel);
        String limited = plan(planBody("science-mock-3", 1, 100000L, null, "EXAM", List.of(science), null, 3, null, true));
        String everything = plan(planBody("all-12m", 12, 900000L, null, "ALL", List.of(), null, null, null, true));
        buy(limited, "LKR", "stack-00001");
        buy(everything, "LKR", "stack-00002");
        for (int i = 0; i < 5; i++) {
            assertThat((String) read(start(studentId, science, "MOCK"), "$.reason")).isEqualTo("ACTIVE_ENTITLEMENT");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM entitlement_use u JOIN entitlement e ON e.id = u.entitlement_id
                WHERE e.scope_type = 'ALL'
                """, Integer.class)).isEqualTo(5);
        assertThat(access(science, 1, "access")).isEqualTo("OPEN");
    }

    @Test
    void aRetriedStartIsCountedOnce() throws Exception {
        UUID science = exam("Science", olevel);
        buy(plan(planBody("science-mock-1", 1, 100000L, null, "EXAM", List.of(science), null, 1, null, true)), "LKR",
                "retry-00001");
        Map<String, Object> body = Map.of("studentId", studentId.toString(), "examId", science.toString(), "mode", "MOCK",
                "consume", true, "attemptId", UUID.randomUUID().toString());
        for (int i = 0; i < 3; i++) {
            assertThat((Boolean) read(expect(call(post("/internal/access-decisions"), DECIDER, body), 200), "$.allowed"))
                    .isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entitlement_use", Integer.class)).isEqualTo(1);
    }

    @Test
    void plansAreValidatedAndOnlyUnpaidPlansCanBeDeleted() throws Exception {
        UUID science = exam("Science", olevel);
        UUID maths = exam("Mathematics", olevel);
        expect(call(post("/api/admin/billing/plans"), ADMIN,
                planBody("two-exams", 1, 100L, null, "EXAM", List.of(science, maths), null, null, null, true)), 400);
        expect(call(post("/api/admin/billing/plans"), ADMIN,
                planBody("one-bundle", 1, 100L, null, "BUNDLE", List.of(science), null, null, null, true)), 400);
        expect(call(post("/api/admin/billing/plans"), ADMIN,
                planBody("ghost", 1, 100L, null, "EXAM", List.of(UUID.randomUUID()), null, null, null, true)), 400);
        expect(call(post("/api/admin/billing/plans"), ADMIN,
                planBody("bad-scope", 1, 100L, null, "SUBJECT", List.of(), null, null, null, true)), 400);

        String unused = plan(planBody("unused", 1, 100000L, null, "EXAM", List.of(science), null, null, null, false));
        expect(call(delete("/api/admin/billing/plans/" + unused), ADMIN, null), 204);
        String paid = plan(planBody("paid", 1, 100000L, null, "EXAM", List.of(science), null, null, null, true));
        buy(paid, "LKR", "paid-000001");
        expect(call(delete("/api/admin/billing/plans/" + paid), ADMIN, null), 409);
        expect(call(delete("/api/admin/billing/plans/" + paid), student, null), 403);

        // A plan with no USD price can't be paid in USD; hidden plans aren't listed.
        expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", "usd-attempt-1"), student,
                Map.of("planId", paid, "currency", "USD", "customer", customer())), 400);
        expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", "eur-attempt-1"), student,
                Map.of("planId", paid, "currency", "EUR", "customer", customer())), 400);
        Map<String, Object> hide = planBody(null, 1, 100000L, null, "EXAM", List.of(science), null, null, null, false);
        expect(call(put("/api/admin/billing/plans/" + paid).header("If-Match", "\"0\""), ADMIN, hide), 200);
        assertThat((List<?>) read(expect(call(get("/api/plans"), null, null), 200), "$.plans")).isEmpty();
        // What the student bought is unaffected.
        assertThat((Boolean) read(expect(call(get("/api/access/me"), student, null), 200), "$.premium")).isTrue();
    }
}
