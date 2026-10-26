package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leenex.exam.platform.security.PlatformJwt;
import com.leenex.exam.platform.security.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Phase 9: question delivery to assessment-service, topics straight under a subject, and exam events. */
class DeliveryTests extends CatalogTestSupport {

    static final String ASSESSMENT = TestTokens.builder().subject("assessment-service").roles(PlatformJwt.ROLE_SERVICE)
            .scope("catalog.internal").build();

    UUID sampleExam() throws Exception {
        return UUID.fromString(read(expect(adminPost("/api/admin/catalog/sample-exams/ol-science", null), 201), "$.id"));
    }

    @Test
    void thePoolHasTheExamSettingsTopicsAndPublishedQuestionsWithAnswerKeys() throws Exception {
        UUID exam = sampleExam();
        MockHttpServletResponse all = expect(call(get("/internal/exams/" + exam + "/question-pool"), ASSESSMENT, null), 200);
        assertThat((String) read(all, "$.status")).isEqualTo("DRAFT");
        assertThat((List<String>) read(all, "$.topics[*].title")).hasSize(10).startsWith("Human Body", "Reflection of Light");
        assertThat((List<Object>) read(all, "$.questions")).hasSize(90);
        assertThat((List<String>) read(all, "$.modes[*].mode")).containsExactly("PRACTICE", "MOCK", "USER_FRIENDLY");
        assertThat((List<Boolean>) read(all, "$.questions[0].options[*].correct")).contains(true);

        String humanBody = read(all, "$.topics[0].id");
        String light = read(all, "$.topics[1].id");
        MockHttpServletResponse two = expect(call(get("/internal/exams/" + exam + "/question-pool")
                .param("topicIds", humanBody, light, UUID.randomUUID().toString()), ASSESSMENT, null), 200);
        List<String> topics = read(two, "$.questions[*].topicId");
        assertThat(topics).hasSize(18).containsOnly(humanBody, light);
        assertThat((List<String>) read(two, "$.questions[*].difficulty")).contains("SIMPLE", "MEDIUM", "HARD");

        // Display content of chosen versions, without answer keys.
        List<String> versions = read(two, "$.questions[0:3].versionId");
        MockHttpServletResponse content = expect(call(post("/internal/question-versions"), ASSESSMENT,
                Map.of("versionIds", versions)), 200);
        assertThat((List<String>) read(content, "$[*].versionId")).containsExactlyInAnyOrderElementsOf(versions);
        assertThat(content.getContentAsString()).doesNotContain("\"correct\"").contains("stemHtml", "explanationHtml");
        expect(call(post("/internal/question-versions"), ASSESSMENT, Map.of("versionIds", List.of(UUID.randomUUID()))), 400);
    }

    @Test
    void onlyServicesWithTheScopeMayReadThePool() throws Exception {
        UUID exam = sampleExam();
        String noScope = TestTokens.builder().subject("subscription-service").roles(PlatformJwt.ROLE_SERVICE)
                .scope("billing.access").build();
        expect(call(get("/internal/exams/" + exam + "/question-pool"), noScope, null), 403);
        expect(call(get("/internal/exams/" + exam + "/question-pool"), ADMIN, null), 403);
        expect(call(get("/internal/exams/" + exam + "/question-pool"), STUDENT, null), 403);
        expect(call(get("/internal/exams/" + UUID.randomUUID() + "/question-pool"), ASSESSMENT, null), 404);
    }

    @Test
    void topicsCanBeAddedStraightUnderASubject() throws Exception {
        UUID subject = UUID.fromString(read(expect(adminPost("/api/admin/catalog/subjects",
                Map.of("title", unique("Science"))), 201), "$.id"));
        MockHttpServletResponse first = expect(adminPost("/api/admin/catalog/topics",
                Map.of("subjectId", subject.toString(), "title", "Human Body")), 201);
        MockHttpServletResponse second = expect(adminPost("/api/admin/catalog/topics",
                Map.of("subjectId", subject.toString(), "title", "Electricity")), 201);
        assertThat((String) read(second, "$.moduleId")).isEqualTo(read(first, "$.moduleId"));
        expect(adminPost("/api/admin/catalog/topics", Map.of("title", "Nowhere")), 400);
    }

    @Test
    void examEventsCarryFreeAttemptsPerMode() throws Exception {
        UUID exam = sampleExam();
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);
        String payload = jdbc.queryForObject("SELECT payload::text FROM catalog.outbox_event WHERE aggregate_id = ? "
                + "AND event_type = 'ExamPublished' ORDER BY seq DESC LIMIT 1", String.class, exam.toString());
        assertThat((Map<String, Integer>) com.jayway.jsonpath.JsonPath.read(payload, "$.freeAttempts"))
                .isEqualTo(Map.of("PRACTICE", 3, "MOCK", 1, "USER_FRIENDLY", 3));
    }
}
