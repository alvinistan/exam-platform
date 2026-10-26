package com.leenex.exam.assessment.client;

import com.leenex.exam.assessment.config.AssessmentProperties;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** catalog-service's question delivery (ADR-008): the pool a session is chosen from, and display content. */
@Component
public class CatalogClient {

    public record DifficultyMix(int simple, int medium, int hard) {
    }

    /** The parts of an exam mode's settings this service uses. */
    public record ModeSettings(String mode, boolean enabled, int minCount, int maxCount, Integer timeLimitSeconds,
                               boolean shuffleQuestions, boolean shuffleOptions, String feedbackTiming,
                               BigDecimal negativeMark, Integer passMarkPct, boolean allowTopicChoice,
                               boolean adaptiveEnabled, DifficultyMix difficultyMix) {
    }

    public record PoolTopic(UUID id, String title, UUID subjectId, String subjectTitle) {
    }

    public record PoolOption(String label, boolean correct, UUID misconceptionId) {
    }

    public record PoolQuestion(UUID questionId, UUID versionId, UUID topicId, String answerType, String difficulty,
                               int estSeconds, BigDecimal marks, List<PoolOption> options) {
    }

    public record Pool(UUID examId, String title, String slug, String status, List<ModeSettings> modes,
                       List<PoolTopic> topics, List<PoolQuestion> questions) {

        public boolean published() {
            return "PUBLISHED".equals(status);
        }
    }

    public record DeliveredOption(String label, String bodyHtml, String mediaUrl, String mediaAlt) {
    }

    public record Definition(String term, String meaning) {
    }

    public record Delivered(UUID versionId, UUID questionId, String stemHtml, String stemMediaUrl, String stemMediaAlt,
                            List<DeliveredOption> options, String explanationHtml, List<String> keyPoints,
                            List<Definition> definitions) {
    }

    private final InternalHttp http;
    private final JsonMapper json;
    private final String base;

    public CatalogClient(InternalHttp http, JsonMapper json, AssessmentProperties properties) {
        this.http = http;
        this.json = json;
        this.base = properties.catalogUrl().toString().replaceAll("/+$", "");
    }

    /** The exam's settings, topics and questions; {@code topicIds} empty means every topic. */
    public Pool pool(UUID examId, Collection<UUID> topicIds) {
        String query = topicIds.isEmpty() ? "" : "?topicIds=" + URLEncoder.encode(
                topicIds.stream().map(UUID::toString).collect(Collectors.joining(",")), StandardCharsets.UTF_8);
        try {
            return json.treeToValue(http.get(URI.create(base + "/internal/exams/" + examId + "/question-pool" + query)),
                    Pool.class);
        } catch (InternalHttp.CallFailed e) {
            if (e.failure().status() == 404) {
                throw new ApiException(ProblemCode.EXAM_UNAVAILABLE, "This exam isn't available at the moment.");
            }
            throw InternalHttp.unavailable();
        }
    }

    /** Display content keyed by version ID. */
    public Map<UUID, Delivered> content(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode body = http.post(URI.create(base + "/internal/question-versions"), Map.of("versionIds", versionIds));
            return Arrays.stream(json.treeToValue(body, Delivered[].class))
                    .collect(Collectors.toMap(Delivered::versionId, d -> d));
        } catch (InternalHttp.CallFailed e) {
            throw new ApiException(ProblemCode.INTERNAL_ERROR, "The questions couldn't be loaded. Please try again.");
        }
    }
}
