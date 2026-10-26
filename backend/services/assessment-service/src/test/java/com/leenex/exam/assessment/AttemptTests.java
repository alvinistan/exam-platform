package com.leenex.exam.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.leenex.exam.assessment.attempt.AttemptService;
import com.leenex.exam.platform.security.PlatformJwt;
import com.leenex.exam.platform.security.TestTokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 9: the exam flow end to end against a real PostgreSQL, with identity-service (token), catalog-service
 * (question pool and content) and subscription-service (access decisions) stubbed over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttemptTests {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final String CLIENT_SECRET = "assessment-test-secret";
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final HttpServer STUB = startStub();

    static final UUID EXAM = UUID.randomUUID();
    static final UUID HUMAN_BODY = UUID.randomUUID();
    static final UUID ELECTRICITY = UUID.randomUUID();
    /** Each question's correct option is stored as B; options read "opt-A", "opt-B" … so tests can find it. */
    static final List<Map<String, Object>> QUESTIONS = new ArrayList<>();
    static final AtomicReference<Map<String, Object>> DECISION = new AtomicReference<>();
    static final List<JsonNode> DECISIONS_ASKED = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> EXAM_STATUS = new AtomicReference<>("PUBLISHED");
    static final AtomicReference<Boolean> MOCK_FEEDBACK_IMMEDIATE = new AtomicReference<>(false);

    static {
        POSTGRES.start();
        for (UUID topic : List.of(HUMAN_BODY, ELECTRICITY)) {
            for (int i = 0; i < 6; i++) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("questionId", UUID.randomUUID().toString());
                q.put("versionId", UUID.randomUUID().toString());
                q.put("topicId", topic.toString());
                q.put("answerType", "SINGLE");
                q.put("difficulty", List.of("SIMPLE", "MEDIUM", "HARD").get(i % 3));
                q.put("estSeconds", 60);
                q.put("marks", 1);
                q.put("options", List.of(option("A", false), option("B", true), option("C", false), option("D", false)));
                QUESTIONS.add(q);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("platform.security.jwks-uri", () -> base + "/.well-known/jwks.json");
        registry.add("assessment.catalog-url", () -> base);
        registry.add("assessment.subscription-url", () -> base);
        registry.add("assessment.service-client.token-uri", () -> base + "/oauth2/token");
        registry.add("assessment.service-client.client-secret", () -> CLIENT_SECRET);
        registry.add("assessment.timing.sweep-enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    AttemptService attempts;

    final UUID student = UUID.randomUUID();
    final String token = TestTokens.student(student);

    @BeforeEach
    void reset() {
        DECISION.set(Map.of("allowed", true, "bank", "MAIN", "reason", "FREE_ATTEMPT", "attemptsLeft", 2));
        DECISIONS_ASKED.clear();
        EXAM_STATUS.set("PUBLISHED");
        MOCK_FEEDBACK_IMMEDIATE.set(false);
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    MockHttpServletResponse call(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    static MockHttpServletResponse expect(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        return r;
    }

    static <T> T read(MockHttpServletResponse r, String path) throws Exception {
        return JsonPath.read(r.getContentAsString(), path);
    }

    Map<String, Object> startBody(String mode, List<UUID> topics, String sizeBy, Integer amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("examId", EXAM.toString());
        body.put("mode", mode);
        body.put("topicIds", topics.stream().map(UUID::toString).toList());
        body.put("sizeBy", sizeBy);
        body.put("COUNT".equals(sizeBy) ? "questionCount" : "minutes", amount);
        return body;
    }

    MockHttpServletResponse start(String mode, List<UUID> topics, String sizeBy, Integer amount) throws Exception {
        return call(post("/api/attempts"), token, startBody(mode, topics, sizeBy, amount));
    }

    /** The display letter of the correct (stored B) option, or of a wrong one. */
    static String letter(MockHttpServletResponse attempt, int position, boolean correct) throws Exception {
        List<String> bodies = read(attempt, "$.questions[" + (position - 1) + "].options[*].bodyHtml");
        List<String> letters = read(attempt, "$.questions[" + (position - 1) + "].options[*].label");
        for (int i = 0; i < bodies.size(); i++) {
            if (bodies.get(i).equals("opt-B") == correct) {
                return letters.get(i);
            }
        }
        throw new IllegalStateException();
    }

    MockHttpServletResponse answer(String attemptId, int position, List<String> selected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("selected", selected);
        body.put("timeSpentMs", 5000);
        return call(put("/api/attempts/" + attemptId + "/answers/" + position), token, body);
    }

    // --- practice -----------------------------------------------------------------------------------------------------

    @Test
    void practiceChecksEachAnswerAtOnceAndScoresOnTheServer() throws Exception {
        MockHttpServletResponse started = expect(start("PRACTICE", List.of(HUMAN_BODY), "COUNT", 4), 201);
        String id = read(started, "$.id");
        assertThat((String) read(started, "$.feedback")).isEqualTo("IMMEDIATE");
        assertThat((List<String>) read(started, "$.questions[*].topicId")).hasSize(4).containsOnly(HUMAN_BODY.toString());
        assertThat((List<Object>) read(started, "$.questions[*].review")).containsOnlyNulls();
        assertThat(started.getContentAsString()).doesNotContain("correctLabels");
        assertThat((String) read(started, "$.examTitle")).isEqualTo("O/L Science");

        JsonNode asked = DECISIONS_ASKED.getFirst();
        assertThat(asked.get("consume").asBoolean()).isTrue();
        assertThat(asked.get("attemptId").asString()).isEqualTo(id);
        assertThat(asked.get("mode").asString()).isEqualTo("PRACTICE");

        MockHttpServletResponse right = expect(answer(id, 1, List.of(letter(started, 1, true))), 200);
        assertThat((Boolean) read(right, "$.review.correct")).isTrue();
        assertThat((List<String>) read(right, "$.review.correctLabels")).containsExactly(letter(started, 1, true));
        assertThat((String) read(right, "$.review.explanationHtml")).isEqualTo("<p>Because.</p>");
        expect(answer(id, 1, List.of(letter(started, 1, false))), 409); // checked answers are final

        MockHttpServletResponse wrong = expect(answer(id, 2, List.of(letter(started, 2, false))), 200);
        assertThat((Boolean) read(wrong, "$.review.correct")).isFalse();
        assertThat((List<String>) read(wrong, "$.review.correctLabels")).containsExactly(letter(started, 2, true));
        expect(answer(id, 3, List.of("A", "B")), 400); // single-answer question
        expect(answer(id, 3, List.of("E")), 400);      // only four options

        MockHttpServletResponse done = expect(call(post("/api/attempts/" + id + "/submit"), token, null), 200);
        assertThat((String) read(done, "$.status")).isEqualTo("SCORED");
        assertThat((String) read(done, "$.finishReason")).isEqualTo("SUBMITTED");
        assertThat((Integer) read(done, "$.result.correct")).isEqualTo(1);
        assertThat((Integer) read(done, "$.result.incorrect")).isEqualTo(1);
        assertThat((Integer) read(done, "$.result.unanswered")).isEqualTo(2);
        assertThat((Integer) read(done, "$.result.percent")).isEqualTo(25);
        assertThat((String) read(done, "$.result.byTopic[0].title")).isEqualTo("Human Body");
        assertThat((Integer) read(done, "$.result.byTopic[0].total")).isEqualTo(4);
        // After finishing every question shows its answer, answered or not.
        assertThat((List<Object>) read(done, "$.questions[*].review.correctLabels")).hasSize(4);

        // Submitting again changes nothing; answering a finished attempt is refused.
        expect(call(post("/api/attempts/" + id + "/submit"), token, null), 200);
        assertThat((String) read(expect(answer(id, 3, List.of("A")), 409), "$.code")).isEqualTo("ATTEMPT_FINISHED");

        List<Map<String, Object>> history = JsonPath.read(expect(call(get("/api/attempts").param("examId", EXAM.toString()),
                token, null), 200).getContentAsString(), "$");
        assertThat(history).anySatisfy(h -> assertThat(h.get("id")).isEqualTo(id));
    }

    @Test
    void anotherStudentCannotSeeOrTouchAnAttempt() throws Exception {
        String id = read(expect(start("PRACTICE", List.of(), "COUNT", 2), 201), "$.id");
        String other = TestTokens.student(UUID.randomUUID());
        expect(call(get("/api/attempts/" + id), other, null), 404);
        expect(call(put("/api/attempts/" + id + "/answers/1"), other, Map.of("selected", List.of("A"))), 404);
        expect(call(post("/api/attempts/" + id + "/submit"), other, null), 404);
        expect(call(get("/api/attempts/" + id), null, null), 401);
    }

    // --- mock ---------------------------------------------------------------------------------------------------------

    @Test
    void aMockExamKeepsAnswersHiddenUntilSubmittedAndHasAServerTimer() throws Exception {
        MockHttpServletResponse started = expect(start("MOCK", List.of(), "TIME", 10), 201);
        String id = read(started, "$.id");
        assertThat((String) read(started, "$.feedback")).isEqualTo("AFTER_SUBMIT");
        java.time.OffsetDateTime begun = java.time.OffsetDateTime.parse(read(started, "$.startedAt"));
        java.time.OffsetDateTime ends = java.time.OffsetDateTime.parse(read(started, "$.expiresAt"));
        assertThat(java.time.Duration.between(begun, ends).toMinutes()).isEqualTo(10);
        assertThat((List<String>) read(started, "$.questions[*].position")).hasSize(10); // 60 s each, 10 minutes

        // Answers can be changed and flagged, and nothing is revealed.
        MockHttpServletResponse saved = expect(answer(id, 1, List.of(letter(started, 1, false))), 200);
        assertThat((Object) read(saved, "$.review")).isNull();
        expect(answer(id, 1, List.of(letter(started, 1, true))), 200);
        expect(call(put("/api/attempts/" + id + "/answers/2"), token, Map.of("flagged", true)), 200);
        MockHttpServletResponse during = expect(call(get("/api/attempts/" + id), token, null), 200);
        assertThat(during.getContentAsString()).doesNotContain("correctLabels");
        assertThat((Boolean) read(during, "$.questions[1].flagged")).isTrue();
        assertThat((List<String>) read(during, "$.questions[0].selected")).containsExactly(letter(started, 1, true));

        // One mock exam at a time per exam: the open one is offered back.
        MockHttpServletResponse second = expect(start("MOCK", List.of(), "TIME", 10), 409);
        assertThat((String) read(second, "$.attemptId")).isEqualTo(id);

        // Time runs out on the server: a late answer is refused and the attempt is marked with what was given in time.
        jdbc.update("UPDATE attempt SET started_at = now() - interval '20 minutes', expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        assertThat((String) read(expect(answer(id, 3, List.of("A")), 409), "$.code")).isEqualTo("ATTEMPT_EXPIRED");
        MockHttpServletResponse after = expect(call(get("/api/attempts/" + id), token, null), 200);
        assertThat((String) read(after, "$.finishReason")).isEqualTo("EXPIRED");
        assertThat((Integer) read(after, "$.result.correct")).isEqualTo(1);
        assertThat((Integer) read(after, "$.result.unanswered")).isEqualTo(9);
        assertThat((List<Object>) read(after, "$.questions[*].review.correctLabels")).hasSize(10);

        // Now a new mock exam may start.
        expect(start("MOCK", List.of(), "COUNT", 3), 201);
    }

    @Test
    void aMockExamCanBeSetUpToShowAnswersStraightAway() throws Exception {
        MOCK_FEEDBACK_IMMEDIATE.set(true);
        MockHttpServletResponse started = expect(start("MOCK", List.of(), "COUNT", 3), 201);
        assertThat((String) read(started, "$.feedback")).isEqualTo("IMMEDIATE");
        // Three 60-second questions: three minutes.
        java.time.OffsetDateTime begun = java.time.OffsetDateTime.parse(read(started, "$.startedAt"));
        java.time.OffsetDateTime ends = java.time.OffsetDateTime.parse(read(started, "$.expiresAt"));
        assertThat(java.time.Duration.between(begun, ends).toMinutes()).isEqualTo(3);
        String id = read(started, "$.id");
        assertThat((Boolean) read(expect(answer(id, 1, List.of(letter(started, 1, true))), 200), "$.review.correct")).isTrue();
        expect(call(post("/api/attempts/" + id + "/submit"), token, null), 200);
    }

    // --- sizes, topics, access ----------------------------------------------------------------------------------------

    @Test
    void aTimeBasedSessionIsSizedFromEstimatedSolvingTimes() throws Exception {
        MockHttpServletResponse three = expect(start("USER_FRIENDLY", List.of(ELECTRICITY), "TIME", 3), 201);
        // Three 60-second questions fit; an Adaptive exam gives them one at a time.
        assertThat((Integer) read(three, "$.plannedCount")).isEqualTo(3);
        assertThat((List<Object>) read(three, "$.questions")).hasSize(1);
        assertThat((Integer) read(three, "$.estimatedSeconds")).isEqualTo(60);
        assertThat((Integer) read(three, "$.requestedMinutes")).isEqualTo(3);
        assertThat((Object) read(three, "$.expiresAt")).isNull(); // only mock exams have a hard end
        assertThat((String) read(three, "$.feedback")).isEqualTo("IMMEDIATE");

        // Both topics chosen: they take turns.
        MockHttpServletResponse both = expect(start("PRACTICE", List.of(HUMAN_BODY, ELECTRICITY), "COUNT", 4), 201);
        assertThat(new HashSet<>((List<String>) read(both, "$.questions[*].topicId")))
                .containsExactlyInAnyOrder(HUMAN_BODY.toString(), ELECTRICITY.toString());
    }

    @Test
    void startsAreValidated() throws Exception {
        assertThat((String) read(expect(start("PRACTICE", List.of(), "COUNT", 31), 400), "$.errors[0].field"))
                .isEqualTo("questionCount"); // the exam's maximum is 30
        assertThat((String) read(expect(start("PRACTICE", List.of(UUID.randomUUID()), "COUNT", 5), 400), "$.errors[0].field"))
                .isEqualTo("topicIds");
        assertThat((String) read(expect(start("MOCK", List.of(), "TIME", 61), 400), "$.errors[0].field"))
                .isEqualTo("minutes"); // the mock time limit is 60 minutes
        expect(call(post("/api/attempts"), token, Map.of("examId", EXAM.toString(), "mode", "PRACTICE", "sizeBy", "COUNT")), 400);
        expect(call(post("/api/attempts"), token, startBody("SPEED", List.of(), "COUNT", 5)), 400);

        EXAM_STATUS.set("DRAFT");
        assertThat((String) read(expect(start("PRACTICE", List.of(), "COUNT", 5), 404), "$.code")).isEqualTo("EXAM_UNAVAILABLE");
        assertThat(DECISIONS_ASKED).isEmpty();
    }

    @Test
    void usedUpFreeAttemptsLockTheExamTypeUntilPremium() throws Exception {
        DECISION.set(Map.of("allowed", false, "bank", "MAIN", "reason", "FREE_ATTEMPTS_USED", "attemptsLeft", 0));
        int before = jdbc.queryForObject("SELECT count(*) FROM attempt", Integer.class);
        MockHttpServletResponse refused = expect(start("PRACTICE", List.of(), "COUNT", 5), 403);
        assertThat((String) read(refused, "$.code")).isEqualTo("FREE_ALLOWANCE_USED");
        assertThat((String) read(refused, "$.detail")).contains("free Practice attempts");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt", Integer.class)).isEqualTo(before);

        DECISION.set(Map.of("allowed", true, "bank", "MAIN", "reason", "ACTIVE_ENTITLEMENT"));
        assertThat((String) read(expect(start("PRACTICE", List.of(), "COUNT", 5), 201), "$.status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameAttempt() throws Exception {
        MockHttpServletRequestBuilder first = post("/api/attempts").header("Idempotency-Key", "start-123");
        String id = read(expect(call(first, token, startBody("PRACTICE", List.of(), "COUNT", 3)), 201), "$.id");
        MockHttpServletRequestBuilder again = post("/api/attempts").header("Idempotency-Key", "start-123");
        assertThat((String) read(expect(call(again, token, startBody("PRACTICE", List.of(), "COUNT", 3)), 200), "$.id"))
                .isEqualTo(id);
        assertThat(DECISIONS_ASKED).hasSize(1);
    }

    @Test
    void questionsSeenRecentlyComeLast() throws Exception {
        List<String> first = read(expect(start("PRACTICE", List.of(HUMAN_BODY), "COUNT", 3), 201), "$.questions[*].position");
        assertThat(first).hasSize(3);
        Set<String> seen = new HashSet<>(jdbc.queryForList(
                "SELECT q.question_id::text FROM attempt a JOIN attempt_question q ON q.attempt_id = a.id WHERE a.student_id = ?",
                String.class, student));
        expect(start("PRACTICE", List.of(HUMAN_BODY), "COUNT", 3), 201);
        List<String> all = jdbc.queryForList(
                "SELECT q.question_id::text FROM attempt a JOIN attempt_question q ON q.attempt_id = a.id WHERE a.student_id = ?",
                String.class, student);
        assertThat(new HashSet<>(all)).hasSize(6); // six Human Body questions, none repeated
        assertThat(seen).hasSize(3);
    }

    @Test
    void theSweepFinishesExpiredAndAbandonedAttempts() throws Exception {
        String mock = read(expect(start("MOCK", List.of(), "COUNT", 2), 201), "$.id");
        String practice = read(expect(start("PRACTICE", List.of(), "COUNT", 2), 201), "$.id");
        jdbc.update("UPDATE attempt SET started_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' WHERE id = ?::uuid", mock);
        jdbc.update("UPDATE attempt SET started_at = now() - interval '3 days', last_activity_at = now() - interval '2 days' WHERE id = ?::uuid", practice);
        assertThat(attempts.finishDue()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT finish_reason FROM attempt WHERE id = ?::uuid", String.class, mock)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT finish_reason FROM attempt WHERE id = ?::uuid", String.class, practice)).isEqualTo("ABANDONED");
        assertThat(attempts.finishDue()).isZero();
    }

    // --- adaptive (Step B) -------------------------------------------------------------------------------------------

    @Test
    void anAdaptiveExamChoosesEachQuestionAfterThePreviousAnswerAndRaisesTheLevel() throws Exception {
        MockHttpServletResponse started = expect(start("USER_FRIENDLY", List.of(HUMAN_BODY), "COUNT", 6), 201);
        String id = read(started, "$.id");
        assertThat((List<Object>) read(started, "$.questions")).hasSize(1);
        assertThat((Integer) read(started, "$.plannedCount")).isEqualTo(6);
        assertThat((Integer) read(started, "$.questions[0].level")).isEqualTo(2); // everyone starts at Medium

        for (int position = 1; position <= 6; position++) {
            MockHttpServletResponse now = expect(call(get("/api/attempts/" + id), token, null), 200);
            assertThat((List<Object>) read(now, "$.questions")).hasSize(position);
            expect(answer(id, position, List.of(letter(now, position, true))), 200);
        }
        MockHttpServletResponse all = expect(call(get("/api/attempts/" + id), token, null), 200);
        assertThat((List<Object>) read(all, "$.questions")).hasSize(6);
        // Five right answers on Human Body: the sixth question was chosen at the Hard level.
        assertThat((List<Integer>) read(all, "$.questions[*].level")).containsExactly(2, 2, 2, 2, 2, 3);
        assertThat(new HashSet<>((List<String>) read(all, "$.questions[*].topicId"))).containsOnly(HUMAN_BODY.toString());

        MockHttpServletResponse done = expect(call(post("/api/attempts/" + id + "/submit"), token, null), 200);
        assertThat((Integer) read(done, "$.result.correct")).isEqualTo(6);
        assertThat((String) read(done, "$.result.byTopic[0].levelLabel")).isEqualTo("Hard");

        MockHttpServletResponse progress = expect(call(get("/api/progress/exams/" + EXAM), token, null), 200);
        assertThat((Integer) read(progress, "$.answered")).isEqualTo(6);
        assertThat((Integer) read(progress, "$.topics[0].level")).isEqualTo(3);
        assertThat((String) read(progress, "$.topics[0].status")).isEqualTo("STRONG");
        assertThat((Integer) read(progress, "$.topics[0].levelTrend")).isEqualTo(1);
        assertThat((List<Object>) read(progress, "$.sessions")).hasSize(1);
        assertThat((String) read(expect(call(get("/api/progress/exams"), token, null), 200), "$[0].title")).isEqualTo("O/L Science");
    }

    @Test
    void anAdaptiveExamEndsEarlyWhenTheTopicRunsOutOfQuestions() throws Exception {
        // Only six Electricity questions exist; asking for ten plans six.
        MockHttpServletResponse started = expect(start("USER_FRIENDLY", List.of(ELECTRICITY), "COUNT", 10), 201);
        assertThat((Integer) read(started, "$.plannedCount")).isEqualTo(6);
    }

    @Test
    void wrongAnswersLowerTheLevelAndMarkTheTopicForWork() throws Exception {
        String id = read(expect(start("PRACTICE", List.of(ELECTRICITY), "COUNT", 5), 201), "$.id");
        MockHttpServletResponse attempt = expect(call(get("/api/attempts/" + id), token, null), 200);
        for (int position = 1; position <= 5; position++) {
            expect(answer(id, position, List.of(letter(attempt, position, false))), 200);
        }
        MockHttpServletResponse progress = expect(call(get("/api/progress/exams/" + EXAM), token, null), 200);
        assertThat((String) read(progress, "$.topics[0].levelLabel")).isEqualTo("Easy");
        assertThat((String) read(progress, "$.topics[0].status")).isEqualTo("NEEDS_WORK");
        assertThat((List<String>) read(progress, "$.focus")).containsExactly(ELECTRICITY.toString());
        // The session isn't finished, but checked practice answers already count.
        assertThat((List<Object>) read(progress, "$.sessions")).isEmpty();
    }

    @Test
    void mockAnswersCountOnlyOnceThePaperIsFinished() throws Exception {
        MockHttpServletResponse started = expect(start("MOCK", List.of(), "COUNT", 2), 201);
        String id = read(started, "$.id");
        expect(answer(id, 1, List.of(letter(started, 1, true))), 200);
        assertThat((Integer) read(expect(call(get("/api/progress/exams/" + EXAM), token, null), 200), "$.answered")).isZero();
        expect(call(post("/api/attempts/" + id + "/submit"), token, null), 200);
        assertThat((Integer) read(expect(call(get("/api/progress/exams/" + EXAM), token, null), 200), "$.answered")).isEqualTo(1);
        expect(call(get("/api/progress/exams"), null, null), 401);
    }

    // --- stub ---------------------------------------------------------------------------------------------------------

    static Map<String, Object> option(String label, boolean correct) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("label", label);
        o.put("correct", correct);
        o.put("misconceptionId", null);
        return o;
    }

    static Map<String, Object> mode(String mode, Integer timeLimit, String feedback) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("enabled", true);
        m.put("defaultCount", 10);
        m.put("minCount", 1);
        m.put("maxCount", 30);
        m.put("timeLimitSeconds", timeLimit);
        m.put("shuffleQuestions", true);
        m.put("shuffleOptions", true);
        m.put("feedbackTiming", feedback);
        m.put("negativeMark", 0);
        m.put("passMarkPct", 50);
        m.put("allowTopicChoice", true);
        m.put("adaptiveEnabled", false);
        m.put("difficultyMix", Map.of("simple", 30, "medium", 50, "hard", 20));
        m.put("freeAttempts", 3);
        return m;
    }

    static Map<String, Object> pool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("examId", EXAM.toString());
        pool.put("title", "O/L Science");
        pool.put("slug", "ol-science");
        pool.put("status", EXAM_STATUS.get());
        pool.put("modes", List.of(mode("PRACTICE", null, "IMMEDIATE"),
                mode("MOCK", 3600, MOCK_FEEDBACK_IMMEDIATE.get() ? "IMMEDIATE" : "AFTER_SUBMIT"),
                mode("USER_FRIENDLY", null, "IMMEDIATE")));
        pool.put("topics", List.of(
                Map.of("id", HUMAN_BODY.toString(), "title", "Human Body", "subjectId", EXAM.toString(), "subjectTitle", "Science"),
                Map.of("id", ELECTRICITY.toString(), "title", "Electricity", "subjectId", EXAM.toString(), "subjectTitle", "Science")));
        pool.put("questions", QUESTIONS);
        return pool;
    }

    static Map<String, Object> content(String versionId) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> q = QUESTIONS.stream().filter(x -> x.get("versionId").equals(versionId)).findFirst().orElseThrow();
        c.put("versionId", versionId);
        c.put("questionId", q.get("questionId"));
        c.put("stemHtml", "<p>Question " + versionId + "</p>");
        c.put("stemMediaUrl", null);
        c.put("stemMediaAlt", null);
        c.put("options", List.of("A", "B", "C", "D").stream().map(l -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("label", l);
            o.put("bodyHtml", "opt-" + l);
            o.put("mediaUrl", null);
            o.put("mediaAlt", null);
            return o;
        }).toList());
        c.put("explanationHtml", "<p>Because.</p>");
        c.put("keyPoints", List.of("Remember this"));
        c.put("definitions", List.of(Map.of("term", "Term", "meaning", "Meaning")));
        return c;
    }

    private static boolean authorised(HttpExchange ex) {
        return String.valueOf(ex.getRequestHeaders().getFirst("Authorization")).startsWith("Bearer ");
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", ex -> send(ex, 200, TestTokens.jwksJson()));
            server.createContext("/oauth2/token", ex -> {
                String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        ("assessment-service:" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    send(ex, 401, "{\"error\":\"invalid_client\"}");
                    return;
                }
                String t = TestTokens.builder().subject("assessment-service").roles(PlatformJwt.ROLE_SERVICE)
                        .scope("catalog.internal billing.access").build();
                send(ex, 200, "{\"access_token\":\"" + t + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
            });
            server.createContext("/internal/exams/", ex -> {
                if (!authorised(ex)) {
                    send(ex, 401, "{}");
                    return;
                }
                if (!ex.getRequestURI().getPath().contains(EXAM.toString())) {
                    send(ex, 404, "{\"code\":\"NOT_FOUND\"}");
                    return;
                }
                send(ex, 200, MAPPER.writeValueAsString(pool()));
            });
            server.createContext("/internal/question-versions", ex -> {
                if (!authorised(ex)) {
                    send(ex, 401, "{}");
                    return;
                }
                JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
                List<Map<String, Object>> out = new ArrayList<>();
                body.get("versionIds").forEach(v -> out.add(content(v.asString())));
                send(ex, 200, MAPPER.writeValueAsString(out));
            });
            server.createContext("/internal/access-decisions", ex -> {
                if (!authorised(ex)) {
                    send(ex, 401, "{}");
                    return;
                }
                DECISIONS_ASKED.add(MAPPER.readTree(ex.getRequestBody().readAllBytes()));
                send(ex, 200, MAPPER.writeValueAsString(DECISION.get()));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
