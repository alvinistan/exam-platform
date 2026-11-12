package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** The guided setup's server side: restoring archived exams, subjects added from the exam, progress counts, sample. */
class ExamSetupTests extends CatalogTestSupport {

    private static final String EXAMS = "/api/admin/catalog/exams/";

    @Test
    void archivedExamCanBeRestoredToDraftAndKeepsItsAddress() throws Exception {
        String slug = unique("restore");
        UUID exam = publishedExam(createCategory(), slug);
        expect(adminPost(EXAMS + exam + "/archive", null), 200);
        expect(publicGet("/api/exams/by-slug/" + slug), 404);

        MockHttpServletResponse restored = expect(adminPost(EXAMS + exam + "/restore", null), 200);
        assertThat((String) read(restored, "$.status")).isEqualTo("DRAFT");
        assertThat((String) read(restored, "$.slug")).isEqualTo(slug);
        assertThat(auditCount("EXAM_RESTORED", exam)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id = ? AND event_type = 'ExamRestored'",
                Integer.class, exam.toString())).isEqualTo(1);

        // Only archived exams can be restored, and it can go live again.
        expect(adminPost(EXAMS + exam + "/restore", null), 409);
        expect(adminPost(EXAMS + exam + "/publish", null), 200);
        expect(publicGet("/api/exams/by-slug/" + slug), 200);
    }

    @Test
    void subjectsCanBeCreatedAndLinkedFromTheExam() throws Exception {
        UUID exam = createExam(createCategory(), unique("link"), List.of());
        MockHttpServletResponse created = expect(adminPost(EXAMS + exam + "/subjects", Map.of("title", "Science")), 200);
        List<String> ids = read(created, "$.subjectIds");
        assertThat(ids).hasSize(1);
        assertThat((Integer) read(created, "$.subjectCount")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM catalog.subject WHERE id = ?::uuid", String.class, ids.getFirst()))
                .isEqualTo("Science");

        Content other = createContent();
        MockHttpServletResponse linked = expect(adminPost(EXAMS + exam + "/subjects",
                Map.of("subjectId", other.subjectId().toString())), 200);
        assertThat((List<String>) read(linked, "$.subjectIds")).containsExactly(ids.getFirst(), other.subjectId().toString());
        expect(adminPost(EXAMS + exam + "/subjects", Map.of("subjectId", other.subjectId().toString())), 409);

        // Exactly one of subjectId and title.
        expect(adminPost(EXAMS + exam + "/subjects", Map.of()), 400);
        expect(adminPost(EXAMS + exam + "/subjects", Map.of("subjectId", other.subjectId().toString(), "title", "Both")), 400);

        MockHttpServletResponse unlinked = expect(call(delete(EXAMS + exam + "/subjects/" + other.subjectId()), ADMIN, null), 200);
        assertThat((List<String>) read(unlinked, "$.subjectIds")).containsExactly(ids.getFirst());
        expect(call(delete(EXAMS + exam + "/subjects/" + other.subjectId()), ADMIN, null), 404);
        assertThat(auditCount("EXAM_SUBJECT_LINKED", exam)).isEqualTo(2);
    }

    @Test
    void unlinkingThePublishedExamsOnlySubjectIsRefused() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("only-subject"), List.of(content.subjectId()));
        enablePractice(exam, 1);
        insertPublishedQuestions("bank_main", content.topicId(), 1);
        expect(adminPost(EXAMS + exam + "/publish", null), 200);
        MockHttpServletResponse refused = expect(call(delete(EXAMS + exam + "/subjects/" + content.subjectId()), ADMIN, null), 409);
        assertThat((String) read(refused, "$.code")).isEqualTo("PUBLISH_BLOCKED");
        assertThat((String) read(refused, "$.issues[0].step")).isEqualTo("STRUCTURE");
    }

    @Test
    void setupCountsIncludeDraftQuestions() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("counts"), List.of(content.subjectId()));
        createQuestion(content.topicId(), "MAIN", "Still a draft");
        insertPublishedQuestions("bank_main", content.topicId(), 2);
        MockHttpServletResponse detail = adminGet(EXAMS + exam);
        assertThat((Integer) read(detail, "$.draftQuestionCount")).isEqualTo(1);
        assertThat((Integer) read(detail, "$.publishedQuestionCount")).isEqualTo(2);
        assertThat((Integer) read(detail, "$.subjectCount")).isEqualTo(1);
        assertThat((String) read(detail, "$.publishReadiness.issues[0].step")).isEqualTo("MODES");
    }

    @Test
    void sampleExamIsCompleteAndReadyToPublish() throws Exception {
        MockHttpServletResponse first = expect(adminPost("/api/admin/catalog/sample-exams/ol-science", null), 201);
        assertThat((String) read(first, "$.status")).isEqualTo("DRAFT");
        assertThat((String) read(first, "$.title")).contains("(sample)");
        assertThat((String) read(first, "$.slug")).startsWith("ol-science-sample");
        assertThat((Integer) read(first, "$.publishedQuestionCount")).isEqualTo(90);
        assertThat((Integer) read(first, "$.topicCount")).isEqualTo(10);
        assertThat((List<String>) read(first, "$.modes")).containsExactly("PRACTICE", "MOCK", "USER_FRIENDLY");
        assertThat((Boolean) read(first, "$.publishReadiness.ready")).isTrue();
        assertThat((List<Integer>) read(first, "$.modeSettings[*].freeAttempts")).containsExactly(3, 1, 3);
        UUID exam = UUID.fromString(read(first, "$.id"));
        assertThat(auditCount("SAMPLE_EXAM_CREATED", exam)).isEqualTo(1);

        // A second sample gets its own address and reuses the category.
        MockHttpServletResponse second = expect(adminPost("/api/admin/catalog/sample-exams/ol-science", null), 201);
        assertThat((String) read(second, "$.slug")).isNotEqualTo(read(first, "$.slug"));
        assertThat((String) read(second, "$.categoryId")).isEqualTo(read(first, "$.categoryId"));

        expect(adminPost(EXAMS + exam + "/publish", null), 200);
    }

    @Test
    void studentsCannotUseSetupEndpoints() throws Exception {
        UUID exam = createExam(createCategory(), unique("guard"), List.of());
        expect(call(post(EXAMS + exam + "/restore"), STUDENT, null), 403);
        expect(call(post(EXAMS + exam + "/subjects"), STUDENT, Map.of("title", "Nope")), 403);
        expect(call(post("/api/admin/catalog/sample-exams/ol-science"), STUDENT, null), 403);
    }
}
