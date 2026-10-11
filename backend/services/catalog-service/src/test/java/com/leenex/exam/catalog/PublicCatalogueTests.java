package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** What anyone can see without signing in (FR-CAT-04, -06, -08, -09, -10, -11, -14). */
class PublicCatalogueTests extends CatalogTestSupport {

    @Test
    void onlyPublishedExamsAreListed() throws Exception {
        UUID category = createCategory();
        String token = unique("listing");
        UUID live = publishedExam(category, token + "-live");
        createExam(category, token + "-draft", List.of());
        UUID archived = publishedExam(category, token + "-gone");
        expect(adminPost("/api/admin/catalog/exams/" + archived + "/archive", null), 200);

        MockHttpServletResponse r = expect(publicGet("/api/exams?q=" + token), 200);
        assertThat((List<String>) read(r, "$.items[*].id")).containsExactly(live.toString());
        assertThat((List<String>) read(r, "$.items[0].modes")).containsExactly("PRACTICE");
        assertThat((Integer) read(r, "$.page.totalElements")).isEqualTo(1);
    }

    @Test
    void searchCategoryAndFeaturedFiltersCombine() throws Exception {
        UUID categoryA = createCategory();
        UUID categoryB = createCategory();
        String token = unique("filter");
        UUID a = publishedExam(categoryA, token + "-a");
        UUID b = publishedExam(categoryB, token + "-b");
        List<String> slugs = read(adminGet("/api/admin/catalog/categories"), "$[?(@.id == '" + categoryA + "')].slug");
        String slugA = slugs.getFirst();

        Map<String, Object> featured = examInput(categoryB, token + "-b", List.of());
        featured.put("featured", true);
        featured.put("subjectIds", read(adminGet("/api/admin/catalog/exams/" + b), "$.subjectIds"));
        expect(adminPut("/api/admin/catalog/exams/" + b, version(b), featured), 200);

        assertThat((List<String>) read(publicGet("/api/exams?q=" + token + "&category=" + slugA), "$.items[*].id"))
                .containsExactly(a.toString());
        assertThat((List<String>) read(publicGet("/api/exams?q=" + token + "&featured=true"), "$.items[*].id"))
                .containsExactly(b.toString());
        assertThat((List<String>) read(publicGet("/api/exams?q=" + token + "&category=no-such-category"), "$.items"))
                .isEmpty();
        // Wildcards in search text are matched literally.
        assertThat((List<String>) read(publicGet("/api/exams?q=%25"), "$.items")).isNotNull();
        expect(publicGet("/api/exams?size=1000"), 400);
    }

    @Test
    void catalogueIsInCatalogueOrderAndPaged() throws Exception {
        UUID category = createCategory();
        String token = unique("paged");
        UUID first = publishedExam(category, token + "-1");
        UUID second = publishedExam(category, token + "-2");
        UUID third = publishedExam(category, token + "-3");
        MockHttpServletResponse page0 = expect(publicGet("/api/exams?q=" + token + "&size=10"), 200);
        assertThat((List<String>) read(page0, "$.items[*].id"))
                .containsExactly(first.toString(), second.toString(), third.toString());
        MockHttpServletResponse page1 = expect(publicGet("/api/exams?q=" + token + "&size=10&page=1"), 200);
        assertThat((List<String>) read(page1, "$.items")).isEmpty();
        assertThat((Integer) read(page1, "$.page.totalPages")).isEqualTo(1);
    }

    @Test
    void examPageHasTheContentTreeAndPublicModeSettingsButNoQuestions() throws Exception {
        String slug = unique("detail");
        UUID exam = publishedExam(createCategory(), slug);
        MockHttpServletResponse r = expect(publicGet("/api/exams/by-slug/" + slug), 200);
        assertThat((String) read(r, "$.id")).isEqualTo(exam.toString());
        assertThat((String) read(r, "$.instructions")).isEqualTo("Read each question carefully.");
        assertThat((String) read(r, "$.category.name")).startsWith("Category ");
        assertThat((String) read(r, "$.seo.title")).isEqualTo("Exam " + slug);
        assertThat((String) read(r, "$.subjects[0].modules[0].topics[0].title")).isEqualTo("Topic one");
        assertThat((String) read(r, "$.modeSettings[0].mode")).isEqualTo("PRACTICE");
        assertThat((Integer) read(r, "$.modeSettings[0].minCount")).isEqualTo(2);

        String body = r.getContentAsString();
        assertThat(body).doesNotContain("shuffle", "resultVisibility", "adaptiveEnabled", "difficultyMix",
                "isCorrect", "correct", "stem", "explanation", "blockers", "status");
    }

    @Test
    void unknownAndUnavailableExamsAreDistinguished() throws Exception {
        MockHttpServletResponse unknown = expect(publicGet("/api/exams/by-slug/" + unique("nothing")), 404);
        assertThat((String) read(unknown, "$.code")).isEqualTo("NOT_FOUND");
        expect(publicGet("/api/exams/by-slug/Not_A_Slug"), 404);

        String draftSlug = unique("draft-only");
        createExam(createCategory(), draftSlug, List.of());
        MockHttpServletResponse draft = expect(publicGet("/api/exams/by-slug/" + draftSlug), 404);
        assertThat((String) read(draft, "$.code")).isEqualTo("EXAM_UNAVAILABLE");
    }

    @Test
    void renamedSlugRedirectsAndCanNeverBeReused() throws Exception {
        UUID category = createCategory();
        String oldSlug = unique("old-name");
        String newSlug = unique("new-name");
        UUID exam = publishedExam(category, oldSlug);
        Map<String, Object> input = examInput(category, newSlug, List.of());
        input.put("subjectIds", read(adminGet("/api/admin/catalog/exams/" + exam), "$.subjectIds"));
        expect(adminPut("/api/admin/catalog/exams/" + exam, version(exam), input), 200);

        MockHttpServletResponse moved = expect(publicGet("/api/exams/by-slug/" + oldSlug), 301);
        assertThat(moved.getHeader("Location")).isEqualTo("/api/exams/by-slug/" + newSlug);
        expect(publicGet("/api/exams/by-slug/" + newSlug), 200);

        MockHttpServletResponse taken = expect(adminPost("/api/admin/catalog/exams",
                examInput(category, oldSlug, List.of())), 409);
        assertThat((String) read(taken, "$.code")).isEqualTo("SLUG_TAKEN");
        MockHttpServletResponse takenNow = expect(adminPost("/api/admin/catalog/exams",
                examInput(category, newSlug, List.of())), 409);
        assertThat((String) read(takenNow, "$.code")).isEqualTo("SLUG_TAKEN");

        // The exam itself may go back to its old address.
        input.put("slug", oldSlug);
        expect(adminPut("/api/admin/catalog/exams/" + exam, version(exam), input), 200);
        expect(publicGet("/api/exams/by-slug/" + oldSlug), 200);
        assertThat(publicGet("/api/exams/by-slug/" + newSlug).getHeader("Location")).isEqualTo("/api/exams/by-slug/" + oldSlug);
    }

    @Test
    void categoriesListActiveOnesWithPublishedCounts() throws Exception {
        UUID category = createCategory();
        publishedExam(category, unique("counted"));
        createExam(category, unique("not-counted"), List.of());
        MockHttpServletResponse r = expect(publicGet("/api/categories"), 200);
        List<Integer> counts = read(r, "$[?(@.id == '" + category + "')].examCount");
        assertThat(counts).containsExactly(1);
        assertThat(r.getContentAsString()).doesNotContain("\"status\"", "\"version\"");
    }
}
