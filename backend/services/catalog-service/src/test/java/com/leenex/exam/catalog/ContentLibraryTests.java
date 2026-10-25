package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Categories and the reusable subject → module → topic library (FR-CAT-01, -03, -13). */
class ContentLibraryTests extends CatalogTestSupport {

    @Test
    void categoryLifecycle() throws Exception {
        String slug = unique("lifecycle");
        MockHttpServletResponse created = expect(adminPost("/api/admin/catalog/categories",
                Map.of("name", "Nursing", "slug", slug, "description", "Nursing exams", "displayOrder", 3)), 201);
        UUID id = UUID.fromString(read(created, "$.id"));
        assertThat(auditCount("CATEGORY_CREATED", id)).isEqualTo(1);

        MockHttpServletResponse duplicate = expect(adminPost("/api/admin/catalog/categories",
                Map.of("name", "Nursing again", "slug", slug)), 409);
        assertThat((String) read(duplicate, "$.code")).isEqualTo("SLUG_TAKEN");

        expect(adminPut("/api/admin/catalog/categories/" + id, 0, Map.of("name", "Nursing & midwifery", "slug", slug)), 200);
        expect(adminPut("/api/admin/catalog/categories/" + id, 0, Map.of("name", "Stale", "slug", slug)), 409);

        UUID exam = publishedExam(id, unique("in-category"));
        MockHttpServletResponse refused = expect(adminPost("/api/admin/catalog/categories/" + id + "/archive", null), 409);
        assertThat((String) read(refused, "$.detail")).contains("1 published exam");

        expect(adminPost("/api/admin/catalog/exams/" + exam + "/archive", null), 200);
        MockHttpServletResponse archived = expect(adminPost("/api/admin/catalog/categories/" + id + "/archive", null), 200);
        assertThat((String) read(archived, "$.status")).isEqualTo("ARCHIVED");
        List<String> publicIds = read(publicGet("/api/categories"), "$[*].id");
        assertThat(publicIds).doesNotContain(id.toString());

        // New exams can't use an archived category.
        assertThat((String) read(expect(adminPost("/api/admin/catalog/exams",
                examInput(id, unique("x"), List.of())), 400), "$.errors[0].message")).isEqualTo("category is archived");
        expect(adminPost("/api/admin/catalog/categories/" + id + "/restore", null), 200);
    }

    @Test
    void subjectsAreReusableAcrossExams() throws Exception {
        Content content = createContent();
        UUID category = createCategory();
        createExam(category, unique("shared-a"), List.of(content.subjectId()));
        createExam(category, unique("shared-b"), List.of(content.subjectId()));
        MockHttpServletResponse library = expect(adminGet("/api/admin/catalog/subjects"), 200);
        List<Integer> linked = read(library, "$[?(@.id == '" + content.subjectId() + "')].linkedExamCount");
        assertThat(linked).containsExactly(2);
        List<String> topics = read(library, "$[?(@.id == '" + content.subjectId() + "')].modules[0].topics[0].title");
        assertThat(topics).containsExactly("Topic one");
    }

    @Test
    void contentCanOnlyBeAddedUnderActiveParents() throws Exception {
        Content content = createContent();
        expect(adminPut("/api/admin/catalog/modules/" + content.moduleId(), 0,
                Map.of("title", "Module one", "status", "ARCHIVED")), 200);
        MockHttpServletResponse r = expect(adminPost("/api/admin/catalog/topics",
                Map.of("moduleId", content.moduleId().toString(), "title", "Late topic")), 400);
        assertThat((String) read(r, "$.errors[0].message")).isEqualTo("module is archived");
        expect(adminPost("/api/admin/catalog/modules", Map.of("subjectId", UUID.randomUUID().toString(), "title", "Orphan")), 400);
        MockHttpServletResponse code = expect(adminPost("/api/admin/catalog/subjects",
                Map.of("title", "Bad code", "code", "lower case")), 400);
        assertThat((String) read(code, "$.errors[0].field")).isEqualTo("code");
    }

    @Test
    void subjectCodesAreUnique() throws Exception {
        String code = unique("CODE").toUpperCase();
        expect(adminPost("/api/admin/catalog/subjects", Map.of("title", "Pharmacology", "code", code)), 201);
        expect(adminPost("/api/admin/catalog/subjects", Map.of("title", "Pharmacology II", "code", code)), 400);
    }

    @Test
    void topicPrerequisitesCannotLoop() throws Exception {
        Content content = createContent();
        UUID a = content.topicId();
        UUID b = UUID.fromString(read(expect(adminPost("/api/admin/catalog/topics", Map.of(
                "moduleId", content.moduleId().toString(), "title", "Topic two",
                "prerequisiteTopicIds", List.of(a.toString()))), 201), "$.id"));
        UUID c = UUID.fromString(read(expect(adminPost("/api/admin/catalog/topics", Map.of(
                "moduleId", content.moduleId().toString(), "title", "Topic three",
                "prerequisiteTopicIds", List.of(b.toString()))), 201), "$.id"));

        MockHttpServletResponse loop = expect(adminPut("/api/admin/catalog/topics/" + a, 0, Map.of(
                "title", "Topic one", "prerequisiteTopicIds", List.of(c.toString()))), 400);
        assertThat((String) read(loop, "$.errors[0].message")).contains("loop");
        expect(adminPut("/api/admin/catalog/topics/" + a, 0, Map.of(
                "title", "Topic one", "prerequisiteTopicIds", List.of(a.toString()))), 400);
        expect(adminPut("/api/admin/catalog/topics/" + a, 0, Map.of(
                "title", "Topic one", "prerequisiteTopicIds", List.of(UUID.randomUUID().toString()))), 400);

        MockHttpServletResponse ok = expect(adminPut("/api/admin/catalog/topics/" + c, 0, Map.of(
                "title", "Topic three", "prerequisiteTopicIds", List.of(a.toString(), b.toString()))), 200);
        assertThat((List<String>) read(ok, "$.prerequisiteTopicIds")).containsExactlyInAnyOrder(a.toString(), b.toString());
    }
}
