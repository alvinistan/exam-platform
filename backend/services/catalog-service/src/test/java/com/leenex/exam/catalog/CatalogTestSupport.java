package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.leenex.exam.platform.security.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup for catalog-service integration tests: one PostgreSQL container and one JWKS stub for the whole run
 * (so the Spring context is reused), plus helpers that drive the admin API like the web app does.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class CatalogTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final HttpServer JWKS = startJwks();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("platform.security.jwks-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("catalog.media.dir", () -> MEDIA_DIR.toString());
        registry.add("catalog.outbox.visible-after", () -> "0s");
    }

    static final java.nio.file.Path MEDIA_DIR = tempDir();

    private static java.nio.file.Path tempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("catalog-media-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String ADMIN = TestTokens.admin(ADMIN_ID);
    static final String STUDENT = TestTokens.student(UUID.randomUUID());

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    // --- HTTP helpers -------------------------------------------------------------------------------------------------

    MockHttpServletResponse call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse adminGet(String path) throws Exception {
        return call(get(path), ADMIN, null);
    }

    MockHttpServletResponse adminPost(String path, Object body) throws Exception {
        return call(post(path), ADMIN, body);
    }

    MockHttpServletResponse adminPut(String path, int version, Object body) throws Exception {
        return call(put(path).header("If-Match", "\"" + version + "\""), ADMIN, body);
    }

    MockHttpServletResponse publicGet(String path) throws Exception {
        return call(get(path), null, null);
    }

    static <T> T read(MockHttpServletResponse response, String path) throws Exception {
        return JsonPath.read(response.getContentAsString(), path);
    }

    static MockHttpServletResponse expect(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response;
    }

    // --- builders -----------------------------------------------------------------------------------------------------

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    UUID createCategory() throws Exception {
        String slug = unique("cat");
        MockHttpServletResponse r = expect(adminPost("/api/admin/catalog/categories",
                Map.of("name", "Category " + slug, "slug", slug)), 201);
        return UUID.fromString(read(r, "$.id"));
    }

    /** A subject → module → topic chain; returns the subject and topic IDs. */
    record Content(UUID subjectId, UUID moduleId, UUID topicId) {
    }

    Content createContent() throws Exception {
        UUID subject = UUID.fromString(read(expect(adminPost("/api/admin/catalog/subjects",
                Map.of("title", unique("Subject"))), 201), "$.id"));
        UUID module = UUID.fromString(read(expect(adminPost("/api/admin/catalog/modules",
                Map.of("subjectId", subject.toString(), "title", "Module one")), 201), "$.id"));
        UUID topic = UUID.fromString(read(expect(adminPost("/api/admin/catalog/topics",
                Map.of("moduleId", module.toString(), "title", "Topic one")), 201), "$.id"));
        return new Content(subject, module, topic);
    }

    Map<String, Object> examInput(UUID categoryId, String slug, List<UUID> subjectIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Exam " + slug);
        body.put("slug", slug);
        body.put("categoryId", categoryId.toString());
        body.put("shortDescription", "Short description of " + slug);
        body.put("instructions", "Read each question carefully.");
        body.put("subjectIds", subjectIds.stream().map(UUID::toString).toList());
        return body;
    }

    UUID createExam(UUID categoryId, String slug, List<UUID> subjectIds) throws Exception {
        return UUID.fromString(read(expect(adminPost("/api/admin/catalog/exams",
                examInput(categoryId, slug, subjectIds)), 201), "$.id"));
    }

    static Map<String, Object> modeInput(boolean enabled, int min, int def, int max, Integer timeLimit, String feedback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("defaultCount", def);
        body.put("minCount", min);
        body.put("maxCount", max);
        body.put("timeLimitSeconds", timeLimit);
        body.put("difficultyMix", Map.of("simple", 30, "medium", 50, "hard", 20));
        body.put("shuffleQuestions", true);
        body.put("shuffleOptions", true);
        body.put("feedbackTiming", feedback);
        body.put("resultVisibility", "FULL");
        body.put("negativeMark", 0);
        body.put("allowTopicChoice", true);
        return body;
    }

    void enablePractice(UUID examId, int minCount) throws Exception {
        expect(call(put("/api/admin/catalog/exams/" + examId + "/modes/PRACTICE"), ADMIN,
                modeInput(true, minCount, minCount, 50, null, "IMMEDIATE")), 200);
    }

    /** Question authoring arrives in a later phase; tests insert published questions directly. */
    void insertPublishedQuestions(String bank, UUID topicId, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO " + bank + ".question (id, topic_id, status, created_by, updated_by) "
                    + "VALUES (gen_random_uuid(), ?, 'PUBLISHED', ?, ?)", topicId, ADMIN_ID, ADMIN_ID);
        }
    }

    /** A published exam with one topic, Practice enabled (minimum 2) and two published questions. */
    UUID publishedExam(UUID categoryId, String slug) throws Exception {
        Content content = createContent();
        UUID exam = createExam(categoryId, slug, List.of(content.subjectId()));
        enablePractice(exam, 2);
        insertPublishedQuestions("bank_main", content.topicId(), 2);
        expect(adminPost("/api/admin/catalog/exams/" + exam + "/publish", null), 200);
        return exam;
    }

    /** A complete single-answer question body (4 options, B correct) for the given topic and bank. */
    static Map<String, Object> questionInput(UUID topicId, String bank, String stem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", topicId.toString());
        body.put("bank", bank);
        body.put("answerType", "SINGLE");
        body.put("stemHtml", "<p>" + stem + "</p>");
        body.put("options", List.of(option("<p>50 mg</p>", false), option("<p>100 mg</p>", true),
                option("<p>150 mg</p>", false), option("<p>200 mg</p>", false)));
        body.put("explanationHtml", "<p>20 kg × 5 mg/kg = 100 mg.</p>");
        body.put("definitions", List.of(Map.of("term", "Dose per kg", "meaning", "Drug amount per kilogram")));
        body.put("keyPoints", List.of("Multiply weight by dose per kg"));
        body.put("difficulty", "SIMPLE");
        body.put("estSeconds", 60);
        body.put("tags", List.of("Dosage", "calculation"));
        return body;
    }

    static Map<String, Object> option(String html, boolean correct) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("bodyHtml", html);
        o.put("isCorrect", correct);
        return o;
    }

    UUID createQuestion(UUID topicId, String bank, String stem) throws Exception {
        return UUID.fromString(read(expect(adminPost("/api/admin/catalog/questions",
                questionInput(topicId, bank, stem)), 201), "$.id"));
    }

    MockHttpServletResponse transition(UUID questionId, String to) throws Exception {
        return adminPost("/api/admin/catalog/questions/" + questionId + "/transitions", Map.of("to", to));
    }

    /** Draft → Reviewed → Published through the API. */
    UUID publishedQuestion(UUID topicId, String bank, String stem) throws Exception {
        UUID id = createQuestion(topicId, bank, stem);
        expect(transition(id, "REVIEWED"), 200);
        expect(transition(id, "PUBLISHED"), 200);
        return id;
    }

    int questionVersion(UUID questionId) throws Exception {
        return read(adminGet("/api/admin/catalog/questions/" + questionId), "$.version");
    }

    int version(UUID examId) throws Exception {
        return read(adminGet("/api/admin/catalog/exams/" + examId), "$.version");
    }

    int auditCount(String action, Object target) {
        return jdbc.queryForObject("SELECT count(*) FROM catalog.audit_event WHERE action = ? AND target_id = ?",
                Integer.class, action, target.toString());
    }

    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = TestTokens.jwksJson().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
