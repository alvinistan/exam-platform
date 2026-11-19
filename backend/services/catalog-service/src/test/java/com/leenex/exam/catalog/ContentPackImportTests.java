package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The content files in {@code content/} must import cleanly: the structure is built the way the admin screens build
 * it (topics added straight under a subject go into its "Topics" module), then the file is uploaded through the real
 * import endpoint and every row must be valid.
 */
class ContentPackImportTests extends CatalogTestSupport {

    private static final Path PAST_PAPERS = Path.of("../../../content/ol-science-past-papers/ol-science-past-papers.csv");
    private static final Pattern ROW_START = Pattern.compile("(?m)^(Biology|Chemistry|Physics),Topics,([^,\"]+|\"[^\"]+\"),");

    @Test
    void olSciencePastPapersImportWithoutErrors() throws Exception {
        String csv = Files.readString(PAST_PAPERS, StandardCharsets.UTF_8);

        // Subjects and topics as listed in the file. Subject titles get a unique suffix so the test can run again.
        Map<String, Set<String>> topics = new LinkedHashMap<>();
        Matcher m = ROW_START.matcher(csv);
        while (m.find()) {
            String topic = m.group(2).startsWith("\"") ? m.group(2).substring(1, m.group(2).length() - 1) : m.group(2);
            topics.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(topic);
        }
        assertThat(topics.keySet()).containsExactly("Biology", "Chemistry", "Physics");
        String suffix = " " + unique("t");
        List<UUID> subjectIds = new ArrayList<>();
        for (Map.Entry<String, Set<String>> subject : topics.entrySet()) {
            UUID subjectId = UUID.fromString(read(expect(adminPost("/api/admin/catalog/subjects",
                    Map.of("title", subject.getKey() + suffix)), 201), "$.id"));
            subjectIds.add(subjectId);
            for (String topic : subject.getValue()) {
                expect(adminPost("/api/admin/catalog/topics", Map.of("subjectId", subjectId.toString(), "title", topic)),
                        201);
            }
        }
        String renamed = ROW_START.matcher(csv).replaceAll(r -> Matcher.quoteReplacement(
                r.group(1) + suffix + ",Topics," + r.group(2) + ","));

        UUID examId = createExam(createCategory(), unique("past-papers"), subjectIds);
        MockHttpServletResponse report = expect(mvc.perform(multipart("/api/admin/catalog/question-imports")
                .file(new MockMultipartFile("file", "ol-science-past-papers.csv", "text/csv",
                        renamed.getBytes(StandardCharsets.UTF_8)))
                .param("examId", examId.toString())
                .header("Authorization", "Bearer " + ADMIN)).andReturn().getResponse(), 201);

        assertThat((List<?>) read(report, "$.errors")).isEmpty();
        assertThat((Integer) read(report, "$.validRows")).isEqualTo(68);

        List<String> created = read(expect(adminPost("/api/admin/catalog/question-imports/"
                + read(report, "$.importId") + "/confirm", null), 200), "$.createdQuestionIds");
        assertThat(created).hasSize(68);
        for (String id : created) {
            assertThat((Boolean) read(adminGet("/api/admin/catalog/questions/" + id), "$.validation.valid"))
                    .as("question %s is ready to publish", id).isTrue();
        }
    }
}
