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

/** Question authoring, versions and the review workflow (FR-QB-01 … FR-QB-12). */
class QuestionBankTests extends CatalogTestSupport {

    private static final String Q = "/api/admin/catalog/questions";

    @Test
    void newQuestionIsASanitisedDraftWithNormalisedTags() throws Exception {
        Content content = createContent();
        Map<String, Object> input = questionInput(content.topicId(), "MAIN", "Dose?");
        input.put("stemHtml", "<p onclick=\"steal()\">A child weighs <strong>20 kg</strong>.<script>alert(1)</script></p>"
                + "<img src=x onerror=alert(1)><ul><li>Item</li></ul>");
        input.put("tags", List.of("  Dosage ", "dosage", "Paediatric  Care"));
        MockHttpServletResponse r = expect(adminPost(Q, input), 201);
        assertThat(r.getHeader("ETag")).isEqualTo("\"0\"");
        String stem = read(r, "$.stemHtml");
        assertThat(stem).contains("<strong>20 kg</strong>", "<ul><li>Item</li></ul>")
                .doesNotContain("script", "onclick", "onerror", "<img", "alert");
        assertThat((String) read(r, "$.status")).isEqualTo("DRAFT");
        assertThat((String) read(r, "$.bank")).isEqualTo("MAIN");
        assertThat((Integer) read(r, "$.versionNo")).isEqualTo(1);
        assertThat((List<String>) read(r, "$.tags")).containsExactly("dosage", "paediatric care");
        assertThat((List<String>) read(r, "$.options[*].label")).containsExactly("A", "B", "C", "D");
        assertThat((Boolean) read(r, "$.validation.valid")).isTrue();
        assertThat((String) read(r, "$.topic.topicTitle")).isEqualTo("Topic one");
        assertThat((String) read(r, "$.createdBy")).isEqualTo(ADMIN_ID.toString());
        assertThat(auditCount("QUESTION_CREATED", read(r, "$.id"))).isEqualTo(1);
    }

    @Test
    void incompleteDraftsSaveButCannotBeReviewed() throws Exception {
        Content content = createContent();
        Map<String, Object> input = questionInput(content.topicId(), "MAIN", "Pick two");
        input.put("answerType", "MULTI");
        input.put("options", List.of(option("<p>One</p>", true), option("", false), option("<p>Three</p>", false)));
        MockHttpServletResponse r = expect(adminPost(Q, input), 201);
        assertThat((Boolean) read(r, "$.validation.valid")).isFalse();
        assertThat((List<String>) read(r, "$.validation.problems")).containsExactly(
                "Add 4 or 5 options (there are 3).",
                "One option has no text or image.",
                "A multi-answer question needs at least two correct options (1 marked).");
        UUID id = UUID.fromString(read(r, "$.id"));
        MockHttpServletResponse refused = expect(transition(id, "REVIEWED"), 400);
        assertThat((List<String>) read(refused, "$.problems")).hasSize(3);

        Map<String, Object> single = questionInput(content.topicId(), "MAIN", "Pick one");
        single.put("options", List.of(option("<p>A</p>", true), option("<p>B</p>", true), option("<p>C</p>", false),
                option("<p>D</p>", false)));
        assertThat((List<String>) read(expect(adminPost(Q, single), 201), "$.validation.problems"))
                .containsExactly("A single-answer question needs exactly one correct option (2 marked).");
    }

    @Test
    void shapeErrorsAreRefusedWithTheField() throws Exception {
        Content content = createContent();
        Map<String, Object> noStem = questionInput(content.topicId(), "MAIN", "x");
        noStem.put("stemHtml", "<p> </p><script>x</script>");
        assertThat((String) read(expect(adminPost(Q, noStem), 400), "$.errors[0].field")).isEqualTo("stemHtml");

        Map<String, Object> badSeconds = questionInput(content.topicId(), "MAIN", "x");
        badSeconds.put("estSeconds", 5);
        assertThat((String) read(expect(adminPost(Q, badSeconds), 400), "$.errors[0].field")).isEqualTo("estSeconds");

        Map<String, Object> noBank = questionInput(content.topicId(), "MAIN", "x");
        noBank.remove("bank");
        expect(adminPost(Q, noBank), 400);

        Map<String, Object> badTag = questionInput(content.topicId(), "MAIN", "x");
        badTag.put("tags", List.of("<b>"));
        assertThat((String) read(expect(adminPost(Q, badTag), 400), "$.errors[0].field")).isEqualTo("tags");

        Map<String, Object> unknownTopic = questionInput(UUID.randomUUID(), "MAIN", "x");
        assertThat((String) read(expect(adminPost(Q, unknownTopic), 400), "$.errors[0].message")).isEqualTo("topic does not exist");

        Map<String, Object> sixOptions = questionInput(content.topicId(), "MAIN", "x");
        sixOptions.put("options", List.of(option("a", true), option("b", false), option("c", false), option("d", false),
                option("e", false), option("f", false)));
        expect(adminPost(Q, sixOptions), 400);
    }

    @Test
    void aCompleteDraftCanBeMarkedReadyWithoutReview() throws Exception {
        Content content = createContent();
        UUID id = createQuestion(content.topicId(), "MAIN", "Straight to ready");
        MockHttpServletResponse published = expect(transition(id, "PUBLISHED"), 200);
        assertThat((String) read(published, "$.status")).isEqualTo("PUBLISHED");
        assertThat((Integer) read(published, "$.publishedVersionNo")).isEqualTo(1);
    }

    @Test
    void publishingFreezesAVersionAndLaterEditsStartANewOne() throws Exception {
        Content content = createContent();
        UUID id = createQuestion(content.topicId(), "MAIN", "Original wording");
        expect(transition(id, "REVIEWED"), 200);
        MockHttpServletResponse published = expect(transition(id, "PUBLISHED"), 200);
        assertThat((Integer) read(published, "$.publishedVersionNo")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT frozen_at IS NOT NULL FROM bank_main.question_version WHERE question_id = ?",
                Boolean.class, id)).isTrue();
        assertThat(auditCount("QUESTION_PUBLISHED", id)).isEqualTo(1);

        Map<String, Object> edit = questionInput(content.topicId(), "MAIN", "Better wording");
        MockHttpServletResponse saved = expect(adminPut(Q + "/" + id, questionVersion(id), edit), 200);
        assertThat((String) read(saved, "$.status")).isEqualTo("PUBLISHED");
        assertThat((Integer) read(saved, "$.versionNo")).isEqualTo(2);
        assertThat((Integer) read(saved, "$.publishedVersionNo")).isEqualTo(1);
        assertThat((Boolean) read(saved, "$.hasDraftChanges")).isTrue();
        // Version 1 is untouched: students keep getting the original wording until the change is published.
        assertThat(jdbc.queryForObject("SELECT stem_text FROM bank_main.question_version WHERE question_id = ? AND version_no = 1",
                String.class, id)).isEqualTo("Original wording");

        // A second edit before publishing changes the same draft version.
        edit.put("stemHtml", "<p>Best wording</p>");
        assertThat((Integer) read(expect(adminPut(Q + "/" + id, questionVersion(id), edit), 200), "$.versionNo")).isEqualTo(2);

        MockHttpServletResponse republished = expect(transition(id, "PUBLISHED"), 200);
        assertThat((Integer) read(republished, "$.publishedVersionNo")).isEqualTo(2);
        assertThat((Boolean) read(republished, "$.hasDraftChanges")).isFalse();
        assertThat((List<Integer>) read(republished, "$.history[*].versionNo")).containsExactly(2, 1);
        assertThat((List<Boolean>) read(republished, "$.history[*].published")).containsExactly(true, true);
    }

    @Test
    void unpublishedChangesCanBeDiscarded() throws Exception {
        Content content = createContent();
        UUID id = publishedQuestion(content.topicId(), "MAIN", "Keep me");
        expect(adminPost(Q + "/" + id + "/discard-draft", null), 409);
        expect(adminPut(Q + "/" + id, questionVersion(id), questionInput(content.topicId(), "MAIN", "Throw me away")), 200);
        MockHttpServletResponse back = expect(adminPost(Q + "/" + id + "/discard-draft", null), 200);
        assertThat((Integer) read(back, "$.versionNo")).isEqualTo(1);
        assertThat((String) read(back, "$.stemHtml")).isEqualTo("<p>Keep me</p>");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_main.question_version WHERE question_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void editingAReviewedQuestionSendsItBackToDraft() throws Exception {
        Content content = createContent();
        UUID id = createQuestion(content.topicId(), "TRIAL", "Reviewed once");
        expect(transition(id, "REVIEWED"), 200);
        MockHttpServletResponse saved = expect(adminPut(Q + "/" + id, questionVersion(id),
                questionInput(content.topicId(), "TRIAL", "Changed after review")), 200);
        assertThat((String) read(saved, "$.status")).isEqualTo("DRAFT");
        assertThat((String) read(saved, "$.bank")).isEqualTo("TRIAL");
    }

    @Test
    void savingUsesOptimisticLockingAndKeepsTheBank() throws Exception {
        Content content = createContent();
        UUID id = createQuestion(content.topicId(), "MAIN", "Locked");
        expect(adminPut(Q + "/" + id, 0, questionInput(content.topicId(), "MAIN", "First")), 200);
        assertThat((String) read(expect(adminPut(Q + "/" + id, 0, questionInput(content.topicId(), "MAIN", "Stale")), 409),
                "$.code")).isEqualTo("CONFLICT");
        assertThat((String) read(expect(adminPut(Q + "/" + id, 1, questionInput(content.topicId(), "TRIAL", "Move")), 400),
                "$.errors[0].field")).isEqualTo("bank");
        expect(call(put(Q + "/" + id), ADMIN, questionInput(content.topicId(), "MAIN", "No header")), 400);
        expect(adminGet(Q + "/" + UUID.randomUUID()), 404);
    }

    @Test
    void misconceptionsLinkOnlyToWrongOptions() throws Exception {
        Content content = createContent();
        MockHttpServletResponse created = expect(adminPost("/api/admin/catalog/misconceptions", Map.of(
                "topicId", content.topicId().toString(), "title", "Forgets to multiply by weight",
                "description", "Uses the per-kg dose as the total dose.")), 201);
        String misconceptionId = read(created, "$.id");
        assertThat((List<String>) read(adminGet("/api/admin/catalog/misconceptions?topicId=" + content.topicId()), "$[*].id"))
                .contains(misconceptionId);

        Map<String, Object> onCorrect = questionInput(content.topicId(), "MAIN", "Dose?");
        Map<String, Object> correctOption = option("<p>100 mg</p>", true);
        correctOption.put("misconceptionId", misconceptionId);
        onCorrect.put("options", List.of(option("a", false), correctOption, option("c", false), option("d", false)));
        assertThat((String) read(expect(adminPost(Q, onCorrect), 400), "$.errors[0].field"))
                .isEqualTo("options[1].misconceptionId");

        Map<String, Object> onWrong = questionInput(content.topicId(), "MAIN", "Dose?");
        Map<String, Object> wrongOption = option("<p>5 mg</p>", false);
        wrongOption.put("misconceptionId", misconceptionId);
        onWrong.put("options", List.of(wrongOption, option("b", true), option("c", false), option("d", false)));
        assertThat((String) read(expect(adminPost(Q, onWrong), 201), "$.options[0].misconceptionId")).isEqualTo(misconceptionId);
    }

    @Test
    void archivingAQuestionALiveExamNeedsIsRefused() throws Exception {
        UUID category = createCategory();
        Content content = createContent();
        UUID exam = createExam(category, unique("needs-questions"), List.of(content.subjectId()));
        enablePractice(exam, 2);
        UUID q1 = publishedQuestion(content.topicId(), "MAIN", "One");
        UUID q2 = publishedQuestion(content.topicId(), "MAIN", "Two");
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);

        MockHttpServletResponse refused = expect(transition(q1, "ARCHIVED"), 409);
        assertThat((String) read(refused, "$.code")).isEqualTo("PUBLISH_BLOCKED");
        assertThat((String) read(adminGet(Q + "/" + q1), "$.status")).isEqualTo("PUBLISHED");

        UUID q3 = publishedQuestion(content.topicId(), "MAIN", "Three");
        expect(transition(q1, "ARCHIVED"), 200);
        expect(transition(q1, "DRAFT"), 200);
        assertThat(q2).isNotEqualTo(q3);
    }

    @Test
    void searchFiltersByTextTagContentStatusAndBank() throws Exception {
        Content content = createContent();
        String marker = unique("marker");
        UUID simple = publishedQuestion(content.topicId(), "MAIN", marker + " heart rate");
        Map<String, Object> hard = questionInput(content.topicId(), "TRIAL", marker + " cardiac output");
        hard.put("difficulty", "HARD");
        hard.put("tags", List.of("cardiology"));
        UUID trial = UUID.fromString(read(expect(adminPost(Q, hard), 201), "$.id"));

        MockHttpServletResponse all = expect(adminGet(Q + "?q=" + marker), 200);
        assertThat((Integer) read(all, "$.page.totalElements")).isEqualTo(2);
        assertThat((List<String>) read(adminGet(Q + "?q=" + marker + "&bank=TRIAL"), "$.items[*].id"))
                .containsExactly(trial.toString());
        assertThat((List<String>) read(adminGet(Q + "?q=" + marker + "&status=PUBLISHED"), "$.items[*].id"))
                .containsExactly(simple.toString());
        assertThat((List<String>) read(adminGet(Q + "?topicId=" + content.topicId() + "&tag=Cardiology"), "$.items[*].id"))
                .containsExactly(trial.toString());
        assertThat((List<String>) read(adminGet(Q + "?moduleId=" + content.moduleId() + "&difficulty=HARD"), "$.items[*].id"))
                .containsExactly(trial.toString());
        assertThat((List<String>) read(adminGet(Q + "?subjectId=" + content.subjectId() + "&sort=difficulty"), "$.items[*].id"))
                .containsExactly(simple.toString(), trial.toString());
        MockHttpServletResponse row = adminGet(Q + "?q=" + marker + "&bank=MAIN");
        assertThat((String) read(row, "$.items[0].subjectTitle")).startsWith("Subject-");
        assertThat((String) read(row, "$.items[0].stemText")).isEqualTo(marker + " heart rate");

        UUID exam = createExam(createCategory(), unique("search-exam"), List.of(content.subjectId()));
        assertThat((Integer) read(adminGet(Q + "?examId=" + exam), "$.page.totalElements")).isEqualTo(2);
        assertThat((Integer) read(adminGet(Q + "?examId=" + createExam(createCategory(), unique("empty"), List.of())),
                "$.page.totalElements")).isEqualTo(0);
        expect(adminGet(Q + "?size=7"), 400);
        expect(adminGet(Q + "?status=LIVE"), 400);
        expect(adminGet(Q + "?sort=title"), 400);
    }

    @Test
    void bulkActionsApplyToAllOrNone() throws Exception {
        Content content = createContent();
        Content other = createContent();
        UUID ready = createQuestion(content.topicId(), "MAIN", "Ready");
        Map<String, Object> broken = questionInput(content.topicId(), "MAIN", "Broken");
        broken.put("options", List.of(option("a", true)));
        UUID incomplete = UUID.fromString(read(expect(adminPost(Q, broken), 201), "$.id"));

        MockHttpServletResponse refused = expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(ready, incomplete),
                "action", "SET_STATUS", "status", "REVIEWED")), 400);
        assertThat((String) read(refused, "$.errors[0].field")).isEqualTo(incomplete.toString());
        assertThat((String) read(adminGet(Q + "/" + ready), "$.status")).isEqualTo("DRAFT");

        expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(ready), "action", "SET_STATUS", "status", "REVIEWED")), 200);
        assertThat((String) read(adminGet(Q + "/" + ready), "$.status")).isEqualTo("REVIEWED");

        expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(ready, incomplete), "action", "MOVE_TOPIC",
                "topicId", other.topicId().toString())), 200);
        assertThat((String) read(adminGet(Q + "/" + incomplete), "$.topicId")).isEqualTo(other.topicId().toString());
        expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(ready), "action", "MOVE_TOPIC",
                "topicId", UUID.randomUUID().toString())), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.audit_event WHERE action = 'QUESTIONS_BULK_MOVE_TOPIC'",
                Integer.class)).isPositive();
    }

    @Test
    void changingBankCopiesTheQuestionAndArchivesTheOriginal() throws Exception {
        Content content = createContent();
        UUID original = publishedQuestion(content.topicId(), "MAIN", "Moves to trial");
        MockHttpServletResponse moved = expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(original),
                "action", "CHANGE_BANK", "bank", "TRIAL")), 200);
        String copyId = read(moved, "$.createdIds[0]");
        MockHttpServletResponse copy = expect(adminGet(Q + "/" + copyId), 200);
        assertThat((String) read(copy, "$.bank")).isEqualTo("TRIAL");
        assertThat((String) read(copy, "$.status")).isEqualTo("PUBLISHED");
        assertThat((Integer) read(copy, "$.publishedVersionNo")).isEqualTo(1);
        assertThat((String) read(copy, "$.stemHtml")).isEqualTo("<p>Moves to trial</p>");
        assertThat((String) read(adminGet(Q + "/" + original), "$.status")).isEqualTo("ARCHIVED");

        // Unpublished edits must be published or discarded before a move.
        UUID edited = publishedQuestion(content.topicId(), "MAIN", "Edited");
        expect(adminPut(Q + "/" + edited, questionVersion(edited), questionInput(content.topicId(), "MAIN", "Pending")), 200);
        expect(adminPost(Q + "/bulk", Map.of("questionIds", List.of(edited), "action", "CHANGE_BANK", "bank", "TRIAL")), 400);
    }

    @Test
    void onlyAdministratorsUseTheQuestionBank() throws Exception {
        expect(call(get(Q), STUDENT, null), 403);
        expect(call(post(Q), STUDENT, Map.of()), 403);
        expect(call(get(Q), null, null), 401);
        expect(call(get("/api/admin/catalog/misconceptions"), STUDENT, null), 403);
    }
}
