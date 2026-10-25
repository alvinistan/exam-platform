package com.leenex.exam.catalog.delivery;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only queries that serve questions to assessment-service (ADR-008): the pool a session is chosen from, and the
 * display content of the exact versions an attempt was given. Only the Main bank is served; only published questions
 * with a frozen version are in the pool.
 */
@Repository
public class DeliveryRepository {

    /** At most this many questions are considered for one session; keeps the selection bounded. */
    static final int POOL_LIMIT = 5000;

    public record PoolOption(String label, boolean correct, UUID misconceptionId) {
    }

    public record PoolQuestion(UUID questionId, UUID versionId, UUID topicId, String answerType, String difficulty,
                               int estSeconds, BigDecimal marks, List<PoolOption> options) {
    }

    public record ContentOption(String label, String bodyHtml, String mediaKey, String mediaAlt) {
    }

    public record Content(UUID versionId, UUID questionId, String stemHtml, String stemMediaKey, String stemMediaAlt,
                          List<ContentOption> options, String explanationHtml, List<String> keyPoints,
                          JsonNode definitions) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DeliveryRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Published questions in the given topics, each at its newest frozen version, with the answer key. */
    public List<PoolQuestion> pool(Collection<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return List.of();
        }
        record Row(UUID questionId, UUID versionId, UUID topicId, String answerType, String difficulty, int estSeconds,
                   BigDecimal marks) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT q.id AS qid, v.id AS vid, q.topic_id, v.answer_type, v.difficulty, v.est_seconds, v.marks
                FROM bank_main.question q
                JOIN LATERAL (SELECT * FROM bank_main.question_version v
                              WHERE v.question_id = q.id AND v.frozen_at IS NOT NULL
                              ORDER BY v.version_no DESC LIMIT 1) v ON true
                WHERE q.status = 'PUBLISHED' AND q.topic_id IN (:topics)
                ORDER BY q.id
                LIMIT :limit
                """)
                .param("topics", topicIds).param("limit", POOL_LIMIT)
                .query((rs, n) -> new Row(rs.getObject("qid", UUID.class), rs.getObject("vid", UUID.class),
                        rs.getObject("topic_id", UUID.class), rs.getString("answer_type"), rs.getString("difficulty"),
                        rs.getInt("est_seconds"), rs.getBigDecimal("marks")))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PoolOption>> options = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT version_id, label, is_correct, misconception_id FROM bank_main.question_option
                WHERE version_id IN (:versions) ORDER BY version_id, label
                """)
                .param("versions", rows.stream().map(Row::versionId).toList())
                .query((rs, n) -> options.computeIfAbsent(rs.getObject("version_id", UUID.class), k -> new ArrayList<>())
                        .add(new PoolOption(rs.getString("label").strip(), rs.getBoolean("is_correct"),
                                rs.getObject("misconception_id", UUID.class))))
                .list();
        return rows.stream().map(r -> new PoolQuestion(r.questionId(), r.versionId(), r.topicId(), r.answerType(),
                r.difficulty(), r.estSeconds(), r.marks(), List.copyOf(options.getOrDefault(r.versionId(), List.of()))))
                .toList();
    }

    /** Display content of the given versions (no answer key: assessment-service holds its own snapshot). */
    public List<Content> content(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = List.copyOf(versionIds);
        Map<UUID, List<ContentOption>> options = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT version_id, label, body_html, media_key, media_alt FROM bank_main.question_option
                WHERE version_id IN (:versions) ORDER BY version_id, label
                """)
                .param("versions", ids)
                .query((rs, n) -> options.computeIfAbsent(rs.getObject("version_id", UUID.class), k -> new ArrayList<>())
                        .add(new ContentOption(rs.getString("label").strip(), rs.getString("body_html"),
                                rs.getString("media_key"), rs.getString("media_alt"))))
                .list();
        return jdbc.sql("""
                SELECT id, question_id, stem_html, stem_media_key, stem_media_alt, explanation_html, key_points,
                       definitions::text AS definitions
                FROM bank_main.question_version
                WHERE id IN (:versions) AND frozen_at IS NOT NULL
                """)
                .param("versions", ids)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Array points = rs.getArray("key_points");
                    return new Content(id, rs.getObject("question_id", UUID.class), rs.getString("stem_html"),
                            rs.getString("stem_media_key"), rs.getString("stem_media_alt"),
                            List.copyOf(options.getOrDefault(id, List.of())), rs.getString("explanation_html"),
                            points == null ? List.of() : List.of((String[]) points.getArray()),
                            json.readTree(rs.getString("definitions")));
                })
                .list();
    }
}
