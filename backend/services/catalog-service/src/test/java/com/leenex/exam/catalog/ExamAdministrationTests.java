package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Exam definitions, modes, publishing rules (FR-CAT-02, -04, -05, -07) and who may change them. */
class ExamAdministrationTests extends CatalogTestSupport {

    @Test
    void newExamStartsAsDraftAndIsAudited() throws Exception {
        UUID category = createCategory();
        String slug = unique("draft-exam");
        MockHttpServletResponse r = expect(adminPost("/api/admin/catalog/exams", examInput(category, slug, List.of())), 201);
        assertThat(r.getHeader("ETag")).isEqualTo("\"0\"");
        assertThat((String) read(r, "$.status")).isEqualTo("DRAFT");
        assertThat((List<?>) read(r, "$.modeSettings")).hasSize(3);
        assertThat((Boolean) read(r, "$.modeSettings[1].saved")).isFalse();
        assertThat((Boolean) read(r, "$.publishReadiness.ready")).isFalse();
        UUID id = UUID.fromString(read(r, "$.id"));
        assertThat(auditCount("EXAM_CREATED", id)).isEqualTo(1);
    }

    @Test
    void publishIsRefusedWithEveryReason() throws Exception {
        UUID exam = createExam(createCategory(), unique("empty"), List.of());
        MockHttpServletResponse r = expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 409);
        assertThat((String) read(r, "$.code")).isEqualTo("PUBLISH_BLOCKED");
        assertThat((List<String>) read(r, "$.blockers")).containsExactly(
                "Enable at least one exam mode.", "Link at least one subject that has an active topic.");
    }

    @Test
    void publishNeedsEnoughPublishedMainBankQuestionsForEachEnabledMode() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("counts"), List.of(content.subjectId()));
        enablePractice(exam, 5);
        insertPublishedQuestions("bank_main", content.topicId(), 4);
        insertPublishedQuestions("bank_trial", content.topicId(), 3);
        jdbc.update("INSERT INTO bank_main.question (id, topic_id, status, created_by, updated_by) "
                + "VALUES (gen_random_uuid(), ?, 'DRAFT', ?, ?)", content.topicId(), ADMIN_ID, ADMIN_ID);

        MockHttpServletResponse blocked = expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 409);
        assertThat((List<String>) read(blocked, "$.blockers"))
                .containsExactly("Practice needs at least 5 published questions; 4 published.");
        MockHttpServletResponse detail = adminGet("/api/admin/catalog/exams/" + exam);
        assertThat((Integer) read(detail, "$.publishedQuestionCount")).isEqualTo(4);
        assertThat((Integer) read(detail, "$.trialQuestionCount")).isEqualTo(3);
        assertThat((Integer) read(detail, "$.topicCount")).isEqualTo(1);

        insertPublishedQuestions("bank_main", content.topicId(), 1);
        MockHttpServletResponse published = expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);
        assertThat((String) read(published, "$.status")).isEqualTo("PUBLISHED");
        assertThat((String) read(published, "$.publishedAt")).isNotNull();
        assertThat((Integer) read(published, "$.displayOrder")).isPositive();
        assertThat(auditCount("EXAM_PUBLISHED", exam)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.outbox_event WHERE aggregate_id = ? AND event_type = 'ExamPublished'",
                Integer.class, exam.toString())).isEqualTo(1);

        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 409);
    }

    @Test
    void archivedContentDoesNotCount() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("archived-topic"), List.of(content.subjectId()));
        enablePractice(exam, 1);
        insertPublishedQuestions("bank_main", content.topicId(), 3);
        expect(adminPut("/api/admin/catalog/topics/" + content.topicId(), 0,
                Map.of("title", "Topic one", "status", "ARCHIVED")), 200);
        MockHttpServletResponse blocked = expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 409);
        assertThat((List<String>) read(blocked, "$.blockers")).contains(
                "Link at least one subject that has an active topic.",
                "Practice needs at least 1 published questions; 0 published.");
    }

    @Test
    void publishedExamCannotLoseWhatItNeedsToRun() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("guarded"), List.of(content.subjectId()));
        enablePractice(exam, 2);
        insertPublishedQuestions("bank_main", content.topicId(), 2);
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);

        // Disabling the only mode is refused and nothing changes.
        MockHttpServletResponse disable = call(put("/api/admin/catalog/exams/" + exam + "/modes/PRACTICE"), ADMIN,
                modeInput(false, 2, 2, 50, null, "IMMEDIATE"));
        expect(disable, 409);
        assertThat((String) read(disable, "$.code")).isEqualTo("PUBLISH_BLOCKED");
        assertThat((List<String>) read(adminGet("/api/admin/catalog/exams/" + exam), "$.modes")).containsExactly("PRACTICE");

        // Raising the minimum above the published question count is refused too.
        expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/PRACTICE"), ADMIN,
                modeInput(true, 3, 3, 50, null, "IMMEDIATE")), 409);

        // So is archiving the exam's only topic, or unlinking its only subject.
        expect(adminPut("/api/admin/catalog/topics/" + content.topicId(), 0,
                Map.of("title", "Topic one", "status", "ARCHIVED")), 409);
        Map<String, Object> unlinked = examInput(createCategory(), read(adminGet("/api/admin/catalog/exams/" + exam), "$.slug"),
                List.of());
        expect(adminPut("/api/admin/catalog/exams/" + exam, version(exam), unlinked), 409);
    }

    @Test
    void editingUsesOptimisticLocking() throws Exception {
        UUID category = createCategory();
        String slug = unique("locked");
        UUID exam = createExam(category, slug, List.of());
        Map<String, Object> input = examInput(category, slug, List.of());
        input.put("title", "Renamed exam");
        MockHttpServletResponse ok = expect(adminPut("/api/admin/catalog/exams/" + exam, 0, input), 200);
        assertThat((String) read(ok, "$.title")).isEqualTo("Renamed exam");
        assertThat(ok.getHeader("ETag")).isEqualTo("\"1\"");

        MockHttpServletResponse stale = expect(adminPut("/api/admin/catalog/exams/" + exam, 0, input), 409);
        assertThat((String) read(stale, "$.code")).isEqualTo("CONFLICT");
        expect(call(put("/api/admin/catalog/exams/" + exam), ADMIN, input), 400);
    }

    @Test
    void invalidExamInputListsTheFields() throws Exception {
        Map<String, Object> input = examInput(createCategory(), "Not A Slug", List.of());
        input.put("title", "x");
        MockHttpServletResponse r = expect(adminPost("/api/admin/catalog/exams", input), 400);
        assertThat((List<String>) read(r, "$.errors[*].field")).containsExactlyInAnyOrder("slug", "title");

        Map<String, Object> unknownCategory = examInput(UUID.randomUUID(), unique("x"), List.of());
        assertThat((String) read(expect(adminPost("/api/admin/catalog/exams", unknownCategory), 400), "$.errors[0].field"))
                .isEqualTo("categoryId");

        Map<String, Object> unknownSubject = examInput(createCategory(), unique("x"), List.of(UUID.randomUUID()));
        assertThat((String) read(expect(adminPost("/api/admin/catalog/exams", unknownSubject), 400), "$.errors[0].field"))
                .isEqualTo("subjectIds");
    }

    @Test
    void modeSettingsAreValidatedAcrossFields() throws Exception {
        UUID exam = createExam(createCategory(), unique("modes"), List.of());
        MockHttpServletResponse mock = expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/MOCK"), ADMIN,
                modeInput(true, 10, 40, 100, null, "IMMEDIATE")), 400);
        assertThat((List<String>) read(mock, "$.errors[*].field"))
                .containsExactlyInAnyOrder("timeLimitSeconds", "feedbackTiming");

        MockHttpServletResponse counts = expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/PRACTICE"), ADMIN,
                modeInput(true, 20, 10, 5, null, "IMMEDIATE")), 400);
        assertThat((List<String>) read(counts, "$.errors[*].field")).containsExactlyInAnyOrder("minCount", "maxCount");

        Map<String, Object> badMix = modeInput(true, 5, 10, 50, null, "IMMEDIATE");
        badMix.put("difficultyMix", Map.of("simple", 50, "medium", 50, "hard", 20));
        expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/PRACTICE"), ADMIN, badMix), 400);
        expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/SPEED"), ADMIN, modeInput(true, 5, 10, 50, null, "IMMEDIATE")), 400);

        MockHttpServletResponse saved = expect(call(put("/api/admin/catalog/exams/" + exam + "/modes/MOCK"), ADMIN,
                modeInput(true, 10, 40, 100, 5400, "AFTER_SUBMIT")), 200);
        assertThat((Integer) read(saved, "$.timeLimitSeconds")).isEqualTo(5400);
        assertThat((Boolean) read(saved, "$.saved")).isTrue();
        assertThat((List<String>) read(adminGet("/api/admin/catalog/exams/" + exam), "$.modes")).containsExactly("MOCK");
        assertThat((Integer) read(adminGet("/api/admin/catalog/exams/" + exam), "$.version")).isEqualTo(1);
    }

    @Test
    void catalogueOrderIsUniqueAmongPublishedExams() throws Exception {
        UUID category = createCategory();
        UUID first = publishedExam(category, unique("first"));
        int position = read(adminGet("/api/admin/catalog/exams/" + first), "$.displayOrder");

        Content content = createContent();
        String slug = unique("second");
        Map<String, Object> input = examInput(category, slug, List.of(content.subjectId()));
        input.put("displayOrder", position);
        UUID second = UUID.fromString(read(expect(adminPost("/api/admin/catalog/exams", input), 201), "$.id"));
        enablePractice(second, 1);
        insertPublishedQuestions("bank_main", content.topicId(), 1);
        MockHttpServletResponse blocked = expect(adminPost("/api/admin/catalog/exams/" + second + "/publish", null), 409);
        assertThat(((List<String>) read(blocked, "$.blockers")).getFirst()).startsWith("Catalogue position " + position);

        input.put("displayOrder", null);
        expect(adminPut("/api/admin/catalog/exams/" + second, version(second), input), 200);
        int secondPosition = read(expect(adminPost("/api/admin/catalog/exams/" + second + "/publish", null), 200), "$.displayOrder");
        assertThat(secondPosition).isGreaterThan(position);
    }

    @Test
    void archivingRemovesTheExamFromTheCatalogue() throws Exception {
        String slug = unique("retired");
        UUID exam = publishedExam(createCategory(), slug);
        MockHttpServletResponse archived = expect(adminPost("/api/admin/catalog/exams/" + exam + "/archive", null), 200);
        assertThat((String) read(archived, "$.status")).isEqualTo("ARCHIVED");
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/archive", null), 409);
        assertThat(auditCount("EXAM_ARCHIVED", exam)).isEqualTo(1);

        // An archived exam can be published again once it is still ready.
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);
    }

    @Test
    void adminListFiltersAndReportsReadiness() throws Exception {
        UUID category = createCategory();
        String token = unique("findme");
        UUID draft = createExam(category, token + "-a", List.of());
        UUID live = publishedExam(category, token + "-b");

        MockHttpServletResponse all = expect(adminGet("/api/admin/catalog/exams?q=" + token), 200);
        assertThat((Integer) read(all, "$.page.totalElements")).isEqualTo(2);
        MockHttpServletResponse drafts = expect(adminGet("/api/admin/catalog/exams?q=" + token + "&status=DRAFT"), 200);
        assertThat((List<String>) read(drafts, "$.items[*].id")).containsExactly(draft.toString());
        assertThat((Boolean) read(drafts, "$.items[0].publishReadiness.ready")).isFalse();
        MockHttpServletResponse byCategory = expect(adminGet("/api/admin/catalog/exams?categoryId=" + category
                + "&status=PUBLISHED"), 200);
        assertThat((List<String>) read(byCategory, "$.items[*].id")).containsExactly(live.toString());
        expect(adminGet("/api/admin/catalog/exams?size=7"), 400);
        expect(adminGet("/api/admin/catalog/exams?status=LIVE"), 400);
    }

    @Test
    void onlyAdministratorsCanChangeTheCatalogue() throws Exception {
        UUID exam = createExam(createCategory(), unique("guard"), List.of());
        expect(call(get("/api/admin/catalog/exams"), STUDENT, null), 403);
        expect(call(post("/api/admin/catalog/exams/" + exam + "/publish"), STUDENT, null), 403);
        expect(call(post("/api/admin/catalog/categories"), STUDENT, Map.of("name", "Nope", "slug", "nope")), 403);
        expect(call(get("/api/admin/catalog/subjects"), null, null), 401);
        expect(call(post("/api/admin/catalog/subjects"), null, Map.of("title", "Nope")), 401);
    }

    @Test
    void eventFeedIsForServicesOnly() throws Exception {
        UUID exam = publishedExam(createCategory(), unique("events"));
        String service = com.leenex.exam.platform.security.TestTokens.builder().subject("learning-service")
                .roles(com.leenex.exam.platform.security.PlatformJwt.ROLE_SERVICE).scope("catalog.internal").build();
        MockHttpServletResponse feed = expect(call(get("/internal/events?after=0&limit=500"), service, null), 200);
        List<String> types = read(feed, "$[?(@.payload.examId == '" + exam + "')].type");
        assertThat(types).containsExactly("ExamPublished");
        expect(call(get("/internal/events?after=0"), ADMIN, null), 403);
    }
}
