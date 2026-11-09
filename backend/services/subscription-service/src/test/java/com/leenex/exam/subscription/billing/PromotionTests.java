package com.leenex.exam.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leenex.exam.platform.security.TestTokens;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Phase 11C: sale prices, discount codes and free access campaigns. Prices are always worked out by the server, and
 * campaign access flows through the same entitlements and limits as paid plans.
 */
class PromotionTests extends PaymentTestSupport {

    final UUID olevel = UUID.randomUUID();

    static String inDays(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).toString();
    }

    record Student(UUID id, String token) {
        static Student next() {
            UUID id = UUID.randomUUID();
            return new Student(id, TestTokens.student(id));
        }
    }

    MockHttpServletResponse quote(Student s, String planId, String currency, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", planId);
        body.put("currency", currency);
        body.put("code", code);
        return call(post("/api/subscriptions/quote"), s.token(), body);
    }

    MockHttpServletResponse checkout(Student s, String planId, String currency, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", planId);
        body.put("currency", currency);
        body.put("code", code);
        body.put("customer", customer());
        return call(post("/api/subscriptions/checkout").header("Idempotency-Key", "key-" + UUID.randomUUID()),
                s.token(), body);
    }

    String code(Map<String, Object> body) throws Exception {
        return read(expect(call(post("/api/admin/billing/discount-codes"), ADMIN, body), 201), "$.id");
    }

    static Map<String, Object> percentCode(String code, int percent, Integer maxUses) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("description", "Launch offer");
        body.put("percentOff", percent);
        body.put("allPlans", true);
        body.put("endsAt", inDays(30));
        body.put("maxUses", maxUses);
        body.put("active", true);
        return body;
    }

    static String refusal(MockHttpServletResponse r) throws Exception {
        expect(r, 400);
        assertThat((String) read(r, "$.errors[0].field")).isEqualTo("code");
        return read(r, "$.errors[0].message");
    }

    // --- sale prices ------------------------------------------------------------------------------------------------

    @Test
    void aSalePriceIsShownWhileOnAndCharged() throws Exception {
        Map<String, Object> body = planBody("sale-3m", 3, 900000L, 4500L, "ALL", List.of(), null, null, null, true);
        body.put("salePriceLkrMinor", 600000L);
        body.put("saleStartsAt", inDays(-1));
        body.put("saleEndsAt", inDays(7));
        String planId = plan(body);

        MockHttpServletResponse plans = expect(call(get("/api/plans"), null, null), 200);
        assertThat(((Number) read(plans, "$.plans[0].salePriceLkrMinor")).longValue()).isEqualTo(600000L);
        assertThat((Object) read(plans, "$.plans[0].salePriceUsdMinor")).isNull();
        assertThat((String) read(plans, "$.plans[0].saleEndsAt")).isNotNull();

        Student s = Student.next();
        MockHttpServletResponse q = expect(quote(s, planId, "LKR", null), 200);
        assertThat(((Number) read(q, "$.listPriceMinor")).longValue()).isEqualTo(900000L);
        assertThat(((Number) read(q, "$.totalMinor")).longValue()).isEqualTo(600000L);
        // USD has no sale price: the regular price applies.
        assertThat(((Number) read(expect(quote(s, planId, "USD", null), 200), "$.totalMinor")).longValue())
                .isEqualTo(4500L);

        String tx = read(expect(checkout(s, planId, "LKR", null), 201), "$.transactionId");
        assertThat(jdbc.queryForMap("SELECT amount_minor, list_price_minor FROM payment_transaction WHERE id = ?::uuid", tx))
                .containsEntry("amount_minor", 600000L).containsEntry("list_price_minor", 900000L);
    }

    @Test
    void aSaleMustBeCheaperAndHaveDates() throws Exception {
        Map<String, Object> body = planBody("bad-sale", 1, 300000L, null, "ALL", List.of(), null, null, null, true);
        body.put("salePriceLkrMinor", 300000L);
        body.put("saleStartsAt", inDays(0));
        body.put("saleEndsAt", inDays(3));
        expect(call(post("/api/admin/billing/plans"), ADMIN, body), 400);
        body.put("salePriceLkrMinor", 200000L);
        body.remove("saleEndsAt");
        expect(call(post("/api/admin/billing/plans"), ADMIN, body), 400);
        body.put("saleEndsAt", inDays(3));
        body.put("salePriceUsdMinor", 500L); // not sold in USD
        expect(call(post("/api/admin/billing/plans"), ADMIN, body), 400);
        body.remove("salePriceUsdMinor");
        expect(call(post("/api/admin/billing/plans"), ADMIN, body), 201);
    }

    @Test
    void aSaleThatHasEndedChargesTheRegularPrice() throws Exception {
        Map<String, Object> body = planBody("old-sale", 1, 300000L, null, "ALL", List.of(), null, null, null, true);
        body.put("salePriceLkrMinor", 100000L);
        body.put("saleStartsAt", inDays(-10));
        body.put("saleEndsAt", inDays(-1));
        String planId = plan(body);
        assertThat((Object) read(expect(call(get("/api/plans"), null, null), 200), "$.plans[0].salePriceLkrMinor")).isNull();
        assertThat(((Number) read(expect(quote(Student.next(), planId, "LKR", null), 200), "$.totalMinor")).longValue())
                .isEqualTo(300000L);
    }

    // --- discount codes ---------------------------------------------------------------------------------------------

    @Test
    void aPercentCodeIsCountedPerStudentAndOverall() throws Exception {
        String planId = plan("code-1m", 1, 300000, "LKR", true);
        code(percentCode("launch20", 20, 2));

        Student a = Student.next();
        MockHttpServletResponse q = expect(quote(a, planId, "LKR", " Launch20 "), 200);
        assertThat((String) read(q, "$.code")).isEqualTo("LAUNCH20");
        assertThat(((Number) read(q, "$.discountMinor")).longValue()).isEqualTo(60000L);
        assertThat(((Number) read(q, "$.totalMinor")).longValue()).isEqualTo(240000L);

        String tx = read(expect(checkout(a, planId, "LKR", "LAUNCH20"), 201), "$.transactionId");
        assertThat(jdbc.queryForMap("SELECT amount_minor, discount_minor FROM payment_transaction WHERE id = ?::uuid", tx))
                .containsEntry("amount_minor", 240000L).containsEntry("discount_minor", 60000L);
        // One use per student by default.
        assertThat(refusal(checkout(a, planId, "LKR", "LAUNCH20"))).isEqualTo("You've already used this code.");

        // A payment that fails gives the use back.
        expect(call(post("/api/payments/test/" + tx), a.token(), Map.of("outcome", "FAILED")), 200);
        expect(quote(a, planId, "LKR", "LAUNCH20"), 200);

        Student b = Student.next();
        Student c = Student.next();
        expect(checkout(b, planId, "LKR", "LAUNCH20"), 201);
        expect(checkout(c, planId, "LKR", "LAUNCH20"), 201);
        // Two pending payments use both uses.
        assertThat(refusal(quote(Student.next(), planId, "LKR", "LAUNCH20"))).isEqualTo("This code has been used up.");
        assertThat(((Number) read(expect(call(get("/api/admin/billing/discount-codes"), ADMIN, null), 200),
                "$[0].uses")).intValue()).isEqualTo(2);
    }

    @Test
    void codesAreRefusedWithAReason() throws Exception {
        String planId = plan("rules-1m", 1, 300000, "LKR", true);
        String otherPlan = plan("rules-3m", 3, 800000, "LKR", true);
        Student s = Student.next();
        assertThat(refusal(quote(s, planId, "LKR", "NOPE"))).isEqualTo("This code isn't valid.");

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("code", "USD5");
        amount.put("amountOffUsdMinor", 500L);
        amount.put("allPlans", false);
        amount.put("planIds", List.of(otherPlan));
        amount.put("endsAt", inDays(5));
        amount.put("active", true);
        code(amount);
        assertThat(refusal(quote(s, planId, "LKR", "USD5"))).isEqualTo("This code can't be used for this plan.");
        assertThat(refusal(quote(s, otherPlan, "LKR", "USD5"))).isEqualTo("This code can't be used when paying in LKR.");

        Map<String, Object> later = percentCode("LATER", 10, null);
        later.put("startsAt", inDays(2));
        code(later);
        assertThat(refusal(quote(s, planId, "LKR", "LATER"))).isEqualTo("This code isn't valid.");

        Map<String, Object> off = percentCode("OFF", 10, null);
        off.put("active", false);
        code(off);
        assertThat(refusal(quote(s, planId, "LKR", "OFF"))).isEqualTo("This code isn't valid.");

        String expiring = code(percentCode("SOON", 10, null));
        jdbc.update("UPDATE discount_code SET starts_at = now() - interval '2 days', ends_at = now() - interval '1 day' "
                + "WHERE id = ?::uuid", expiring);
        assertThat(refusal(quote(s, planId, "LKR", "SOON"))).isEqualTo("This code has expired.");

        // Both a percentage and an amount, a duplicate code, and no plans are refused.
        Map<String, Object> both = percentCode("BOTH", 10, null);
        both.put("amountOffLkrMinor", 1000L);
        expect(call(post("/api/admin/billing/discount-codes"), ADMIN, both), 400);
        expect(call(post("/api/admin/billing/discount-codes"), ADMIN, percentCode("soon", 5, null)), 400);
        Map<String, Object> noPlans = percentCode("NOPLANS", 10, null);
        noPlans.put("allPlans", false);
        expect(call(post("/api/admin/billing/discount-codes"), ADMIN, noPlans), 400);
    }

    @Test
    void aCodeThatMakesItFreeStartsThePlanWithoutPaying() throws Exception {
        String planId = plan("free-1m", 1, 300000, "LKR", true);
        code(percentCode("FREEMONTH", 100, null));
        Student s = Student.next();
        // No buyer details are needed when nothing is paid.
        MockHttpServletResponse r = expect(call(post("/api/subscriptions/checkout").header("Idempotency-Key", "free-1"),
                s.token(), Map.of("planId", planId, "currency", "LKR", "code", "freemonth")), 201);
        assertThat((String) read(r, "$.provider")).isEqualTo("FREE");
        assertThat((String) read(r, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) read(r, "$.method")).isEqualTo("GET");
        assertThat((String) read(r, "$.action")).startsWith("https://exams.example.lk/checkout/return?tx=");
        assertThat(premium(s.token())).isTrue();
        assertThat(txStatus(s.token(), read(r, "$.transactionId"))).isEqualTo("SUCCEEDED");
    }

    @Test
    void anAdministratorCanEditACodeWithTheVersion() throws Exception {
        String id = code(percentCode("EDITME", 10, null));
        Map<String, Object> changed = percentCode(null, 15, 50);
        expect(call(put("/api/admin/billing/discount-codes/" + id).header("If-Match", "\"0\""), ADMIN, changed), 200);
        expect(call(put("/api/admin/billing/discount-codes/" + id).header("If-Match", "\"0\""), ADMIN, changed), 409);
        assertThat(jdbc.queryForObject("SELECT percent_off FROM discount_code WHERE id = ?::uuid", Integer.class, id))
                .isEqualTo(15);
        expect(call(post("/api/admin/billing/discount-codes"), TestTokens.student(UUID.randomUUID()),
                percentCode("NOTYOURS", 10, null)), 403);
    }

    // --- campaigns --------------------------------------------------------------------------------------------------

    MockHttpServletResponse campaign(Map<String, Object> body) throws Exception {
        return call(post("/api/admin/billing/campaigns"), ADMIN, body);
    }

    static Map<String, Object> campaignBody(String audience, String scopeType, List<UUID> exams, Integer mock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Exam week");
        body.put("scopeType", scopeType);
        body.put("examIds", exams.stream().map(UUID::toString).toList());
        body.put("practiceLimit", null);
        body.put("mockLimit", mock);
        body.put("adaptiveLimit", 0);
        body.put("audience", audience);
        return body;
    }

    @Test
    void aCampaignForEveryoneGivesAccessUntilItEnds() throws Exception {
        UUID science = exam("Science", olevel);
        UUID maths = exam("Mathematics", olevel);
        Map<String, Object> body = campaignBody("ALL", "EXAM", List.of(science), 1);
        body.put("endsAt", inDays(7));
        String id = read(expect(campaign(body), 201), "$.id");

        MockHttpServletResponse offers = expect(call(get("/api/plans/offers"), null, null), 200);
        assertThat((List<String>) read(offers, "$[*].id")).containsExactly(id);
        assertThat((List<String>) read(offers, "$[0].exams[*].title")).containsExactly("Science");

        Student s = Student.next();
        assertThat(premium(s.token())).isTrue();
        // Mock: 1 campaign attempt, then the 1 free attempt, then locked.
        assertThat((String) read(start(s.id(), science, "MOCK"), "$.reason")).isEqualTo("PLAN_ATTEMPT");
        assertThat((String) read(start(s.id(), science, "MOCK"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        assertThat((Boolean) read(start(s.id(), science, "MOCK"), "$.allowed")).isFalse();
        // Practice unlimited on Science only.
        assertThat((String) read(start(s.id(), science, "PRACTICE"), "$.reason")).isEqualTo("ACTIVE_ENTITLEMENT");
        assertThat((String) read(start(s.id(), maths, "PRACTICE"), "$.reason")).isEqualTo("FREE_ATTEMPT");
        // The student's entitlement was created on first use, once.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entitlement WHERE campaign_id = ?::uuid", Integer.class, id))
                .isEqualTo(1);

        expect(call(post("/api/admin/billing/campaigns/" + id + "/end"), ADMIN, null), 200);
        assertThat(premium(s.token())).isFalse();
        assertThat(premium(Student.next().token())).isFalse();
        assertThat((List<?>) read(expect(call(get("/api/plans/offers"), null, null), 200), "$")).isEmpty();
        expect(call(post("/api/admin/billing/campaigns/" + id + "/end"), ADMIN, null), 409);
    }

    @Test
    void aCampaignForChosenStudentsGivesOnlyThemAccess() throws Exception {
        UUID science = exam("Science", olevel);
        Student chosen = Student.next();
        Student other = Student.next();
        Student later = Student.next();
        Map<String, Object> body = campaignBody("SELECTED", "ALL", List.of(), null);
        body.put("durationMonths", 1);
        body.put("studentIds", List.of(chosen.id().toString(), chosen.id().toString()));
        String id = read(expect(campaign(body), 201), "$.id");
        assertThat(((Number) read(expect(call(get("/api/admin/billing/campaigns"), ADMIN, null), 200), "$[0].students"))
                .intValue()).isEqualTo(1);
        // Not shown as a public offer.
        assertThat((List<?>) read(expect(call(get("/api/plans/offers"), null, null), 200), "$")).isEmpty();

        assertThat(premium(chosen.token())).isTrue();
        assertThat(premium(other.token())).isFalse();
        assertThat((String) read(start(chosen.id(), science, "MOCK"), "$.reason")).isEqualTo("ACTIVE_ENTITLEMENT");

        expect(call(post("/api/admin/billing/campaigns/" + id + "/students"), ADMIN,
                Map.of("studentIds", List.of(later.id().toString()))), 200);
        assertThat(premium(later.token())).isTrue();

        expect(call(post("/api/admin/billing/campaigns/" + id + "/end"), ADMIN, null), 200);
        assertThat(premium(chosen.token())).isFalse();
        assertThat(premium(later.token())).isFalse();
    }

    @Test
    void campaignsAreChecked() throws Exception {
        Map<String, Object> noEnd = campaignBody("ALL", "ALL", List.of(), null);
        expect(campaign(noEnd), 400);
        Map<String, Object> noStudents = campaignBody("SELECTED", "ALL", List.of(), null);
        noStudents.put("durationMonths", 3);
        expect(campaign(noStudents), 400);
        Map<String, Object> badLength = campaignBody("SELECTED", "ALL", List.of(), null);
        badLength.put("durationMonths", 2);
        badLength.put("studentIds", List.of(UUID.randomUUID().toString()));
        expect(campaign(badLength), 400);
        Map<String, Object> examWithoutExam = campaignBody("ALL", "EXAM", List.of(), null);
        examWithoutExam.put("endsAt", inDays(3));
        expect(campaign(examWithoutExam), 400);
        Map<String, Object> ok = campaignBody("ALL", "ALL", List.of(), null);
        ok.put("endsAt", inDays(3));
        expect(call(post("/api/admin/billing/campaigns"), TestTokens.student(UUID.randomUUID()), ok), 403);
    }

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder put(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
    }
}
