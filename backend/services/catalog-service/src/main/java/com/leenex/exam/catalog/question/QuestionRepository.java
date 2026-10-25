package com.leenex.exam.catalog.question;

import com.leenex.exam.catalog.question.QuestionContent.Definition;
import com.leenex.exam.catalog.question.QuestionContent.Option;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data access for both question banks. Every statement is written once and run against the bank's own schema
 * ({@link Bank#table}); nothing here ever joins the two banks' rows together except the admin search.
 */
@Repository
public class QuestionRepository {

    public record QuestionRow(UUID id, Bank bank, UUID topicId, String status, int currentVersionNo, UUID createdBy,
                              UUID updatedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt, int version) {
    }

    public record VersionRow(UUID id, int versionNo, QuestionContent content, OffsetDateTime frozenAt, UUID createdBy,
                             OffsetDateTime createdAt) {
        public boolean frozen() {
            return frozenAt != null;
        }
    }

    private static final TypeReference<List<Definition>> DEFINITIONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public QuestionRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Which bank holds the question, if any. IDs are UUIDv7, so they never collide across banks. */
    public Optional<Bank> bankOf(UUID id) {
        for (Bank bank : Bank.values()) {
            if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + bank.table("question") + " WHERE id = :id)")
                    .param("id", id).query(Boolean.class).single()) {
                return Optional.of(bank);
            }
        }
        return Optional.empty();
    }

    public Optional<QuestionRow> find(Bank bank, UUID id) {
        return jdbc.sql("SELECT * FROM " + bank.table("question") + " WHERE id = :id")
                .param("id", id).query((rs, row) -> question(rs, bank)).optional();
    }

    public Optional<QuestionRow> lock(Bank bank, UUID id) {
        return jdbc.sql("SELECT * FROM " + bank.table("question") + " WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, row) -> question(rs, bank)).optional();
    }

    public void insertQuestion(Bank bank, UUID id, UUID topicId, String status, UUID actor) {
        jdbc.sql("INSERT INTO " + bank.table("question") + " (id, topic_id, status, current_version_no, created_by, updated_by)"
                        + " VALUES (:id, :topic, :status, 1, :actor, :actor)")
                .param("id", id).param("topic", topicId).param("status", status).param("actor", actor).update();
    }

    /** Updates the question row and bumps its optimistic-lock version. */
    public void updateQuestion(Bank bank, UUID id, UUID topicId, String status, int currentVersionNo, UUID actor) {
        jdbc.sql("UPDATE " + bank.table("question") + " SET topic_id = :topic, status = :status,"
                        + " current_version_no = :current, updated_by = :actor, updated_at = now(), version = version + 1"
                        + " WHERE id = :id")
                .param("id", id).param("topic", topicId).param("status", status).param("current", currentVersionNo)
                .param("actor", actor).update();
    }

    // --- versions ---------------------------------------------------------------------------------------------------

    public Optional<VersionRow> version(Bank bank, UUID questionId, int versionNo) {
        return jdbc.sql("SELECT * FROM " + bank.table("question_version") + " WHERE question_id = :q AND version_no = :n")
                .param("q", questionId).param("n", versionNo)
                .query((rs, row) -> versionRow(bank, rs, questionId)).optional();
    }

    /** The newest frozen (published) version: what students are served. */
    public Optional<VersionRow> latestFrozen(Bank bank, UUID questionId) {
        return jdbc.sql("SELECT * FROM " + bank.table("question_version")
                        + " WHERE question_id = :q AND frozen_at IS NOT NULL ORDER BY version_no DESC LIMIT 1")
                .param("q", questionId).query((rs, row) -> versionRow(bank, rs, questionId)).optional();
    }

    public record HistoryRow(int versionNo, OffsetDateTime frozenAt, UUID createdBy, OffsetDateTime createdAt) {
    }

    public List<HistoryRow> history(Bank bank, UUID questionId) {
        return jdbc.sql("SELECT version_no, frozen_at, created_by, created_at FROM " + bank.table("question_version")
                        + " WHERE question_id = :q ORDER BY version_no DESC")
                .param("q", questionId)
                .query((rs, row) -> new HistoryRow(rs.getInt("version_no"), rs.getObject("frozen_at", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public void insertVersion(Bank bank, UUID versionId, UUID questionId, int versionNo, QuestionContent c, String stemText,
                              UUID actor) {
        jdbc.sql("INSERT INTO " + bank.table("question_version") + " (id, question_id, version_no, answer_type, stem_html,"
                        + " stem_text, stem_media_key, stem_media_alt, explanation_html, definitions, key_points, difficulty,"
                        + " est_seconds, marks, created_by) VALUES (:id, :q, :n, :type, :stem, :text, :media, :alt,"
                        + " :explanation, CAST(:definitions AS jsonb), :keyPoints, :difficulty, :seconds, :marks, :actor)")
                .param("id", versionId).param("q", questionId).param("n", versionNo).param("type", c.answerType())
                .param("stem", c.stemHtml()).param("text", stemText).param("media", c.stemMediaKey())
                .param("alt", c.stemMediaAlt()).param("explanation", emptyToNull(c.explanationHtml()))
                .param("definitions", json.writeValueAsString(c.definitions()))
                .param("keyPoints", c.keyPoints().toArray(String[]::new)).param("difficulty", c.difficulty())
                .param("seconds", c.estSeconds()).param("marks", c.marks()).param("actor", actor)
                .update();
        insertOptions(bank, versionId, c.options());
    }

    /** Rewrites an unfrozen version in place (the database refuses this for frozen versions). */
    public void replaceVersion(Bank bank, UUID versionId, QuestionContent c, String stemText) {
        jdbc.sql("DELETE FROM " + bank.table("question_option") + " WHERE version_id = :v").param("v", versionId).update();
        jdbc.sql("UPDATE " + bank.table("question_version") + " SET answer_type = :type, stem_html = :stem, stem_text = :text,"
                        + " stem_media_key = :media, stem_media_alt = :alt, explanation_html = :explanation,"
                        + " definitions = CAST(:definitions AS jsonb), key_points = :keyPoints, difficulty = :difficulty,"
                        + " est_seconds = :seconds, marks = :marks WHERE id = :id")
                .param("id", versionId).param("type", c.answerType()).param("stem", c.stemHtml()).param("text", stemText)
                .param("media", c.stemMediaKey()).param("alt", c.stemMediaAlt())
                .param("explanation", emptyToNull(c.explanationHtml()))
                .param("definitions", json.writeValueAsString(c.definitions()))
                .param("keyPoints", c.keyPoints().toArray(String[]::new)).param("difficulty", c.difficulty())
                .param("seconds", c.estSeconds()).param("marks", c.marks())
                .update();
        insertOptions(bank, versionId, c.options());
    }

    public void freeze(Bank bank, UUID versionId) {
        jdbc.sql("UPDATE " + bank.table("question_version") + " SET frozen_at = now() WHERE id = :id AND frozen_at IS NULL")
                .param("id", versionId).update();
    }

    /** Removes an unfrozen draft version (discarding unpublished changes). */
    public void deleteVersion(Bank bank, UUID versionId) {
        jdbc.sql("DELETE FROM " + bank.table("question_option") + " WHERE version_id = :v").param("v", versionId).update();
        jdbc.sql("DELETE FROM " + bank.table("question_version") + " WHERE id = :v").param("v", versionId).update();
    }

    private void insertOptions(Bank bank, UUID versionId, List<Option> options) {
        for (Option o : options) {
            jdbc.sql("INSERT INTO " + bank.table("question_option") + " (id, version_id, label, body_html, media_key,"
                            + " media_alt, is_correct, misconception_id) VALUES (:id, :v, :label, :body, :media, :alt,"
                            + " :correct, :misconception)")
                    .param("id", com.leenex.exam.platform.web.UuidV7.generate()).param("v", versionId)
                    .param("label", o.label()).param("body", o.bodyHtml()).param("media", o.mediaKey())
                    .param("alt", o.mediaAlt()).param("correct", o.correct()).param("misconception", o.misconceptionId())
                    .update();
        }
    }

    // --- tags -------------------------------------------------------------------------------------------------------

    public List<String> tags(Bank bank, UUID questionId) {
        return jdbc.sql("SELECT tag FROM " + bank.table("question_tag") + " WHERE question_id = :q ORDER BY tag")
                .param("q", questionId).query(String.class).list();
    }

    public void replaceTags(Bank bank, UUID questionId, Collection<String> tags) {
        jdbc.sql("DELETE FROM " + bank.table("question_tag") + " WHERE question_id = :q").param("q", questionId).update();
        for (String tag : tags) {
            jdbc.sql("INSERT INTO " + bank.table("question_tag") + " (question_id, tag) VALUES (:q, :tag)")
                    .param("q", questionId).param("tag", tag).update();
        }
    }

    // --- mapping ----------------------------------------------------------------------------------------------------

    private static QuestionRow question(ResultSet rs, Bank bank) throws SQLException {
        return new QuestionRow(rs.getObject("id", UUID.class), bank, rs.getObject("topic_id", UUID.class),
                rs.getString("status"), rs.getInt("current_version_no"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("version"));
    }

    private VersionRow versionRow(Bank bank, ResultSet rs, UUID questionId) throws SQLException {
        UUID versionId = rs.getObject("id", UUID.class);
        Array keyPoints = rs.getArray("key_points");
        List<Option> options = jdbc.sql("SELECT * FROM " + bank.table("question_option") + " WHERE version_id = :v ORDER BY label")
                .param("v", versionId)
                .query((o, n) -> new Option(o.getString("label").strip(), o.getString("body_html"), o.getString("media_key"),
                        o.getString("media_alt"), o.getBoolean("is_correct"), o.getObject("misconception_id", UUID.class)))
                .list();
        UUID topicId = jdbc.sql("SELECT topic_id FROM " + bank.table("question") + " WHERE id = :q")
                .param("q", questionId).query(UUID.class).single();
        QuestionContent content = new QuestionContent(topicId, rs.getString("answer_type"), rs.getString("stem_html"),
                rs.getString("stem_media_key"), rs.getString("stem_media_alt"), options,
                rs.getString("explanation_html") == null ? "" : rs.getString("explanation_html"),
                json.readValue(rs.getString("definitions"), DEFINITIONS),
                keyPoints == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) keyPoints.getArray())),
                rs.getString("difficulty"), rs.getInt("est_seconds"), rs.getBigDecimal("marks"), List.of());
        return new VersionRow(versionId, rs.getInt("version_no"), content, rs.getObject("frozen_at", OffsetDateTime.class),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static BigDecimal orOne(BigDecimal marks) {
        return marks == null ? BigDecimal.ONE : marks;
    }
}
