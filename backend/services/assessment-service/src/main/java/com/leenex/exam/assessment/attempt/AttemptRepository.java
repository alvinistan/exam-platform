package com.leenex.exam.assessment.attempt;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Attempts, their fixed question sets and responses (assessment database). Plain SQL; callers own transactions. */
@Repository
public class AttemptRepository {

    public record AttemptRow(UUID id, UUID studentId, UUID examId, String examTitle, String examSlug, String mode,
                             String generation, Integer requestedCount, Integer requestedSeconds, String bank,
                             String accessReason, String configJson, String status, String finishReason,
                             OffsetDateTime startedAt, OffsetDateTime expiresAt, OffsetDateTime lastActivityAt,
                             OffsetDateTime finishedAt, BigDecimal score, BigDecimal maxScore, Integer correctCount,
                             Integer incorrectCount, Integer unansweredCount, List<UUID> topicIds, int questionCount) {

        public boolean finished() {
            return "SCORED".equals(status);
        }
    }

    /** One question of an attempt with the student's response. Labels are the stored (catalogue) labels. */
    public record QuestionRow(int position, UUID questionId, UUID versionId, UUID topicId, String topicTitle,
                              String difficulty, int estSeconds, String answerType, List<String> optionOrder,
                              List<String> correctLabels, BigDecimal marks, List<String> selected, boolean flagged,
                              boolean locked, int timeSpentMs, OffsetDateTime answeredAt, Boolean isCorrect,
                              BigDecimal awarded) {
    }

    public record NewAttempt(UUID id, UUID studentId, UUID examId, String examTitle, String examSlug, String mode,
                             String generation, Integer requestedCount, Integer requestedSeconds, String bank,
                             String accessReason, String configJson, OffsetDateTime startedAt,
                             OffsetDateTime expiresAt, String idempotencyKey, List<UUID> topicIds) {
    }

    public record NewQuestion(int position, UUID questionId, UUID versionId, UUID topicId, String topicTitle,
                              String difficulty, int estSeconds, String answerType, List<String> optionOrder,
                              List<String> correctLabels, String misconceptionsJson, BigDecimal marks) {
    }

    private static final String ATTEMPT_COLUMNS = """
            a.*, (SELECT count(*) FROM attempt_question q WHERE q.attempt_id = a.id) AS question_count
            """;

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- writes -------------------------------------------------------------------------------------------------------

    public void insert(NewAttempt a, List<NewQuestion> questions) {
        jdbc.sql("""
                INSERT INTO attempt (id, student_id, exam_id, exam_title, exam_slug, mode, generation, requested_count,
                    requested_seconds, bank, access_reason, config_snapshot, started_at, expires_at, last_activity_at,
                    idempotency_key, topic_ids)
                VALUES (:id, :student, :exam, :title, :slug, :mode, :generation, :count, :seconds, :bank, :reason,
                    CAST(:config AS jsonb), :started, :expires, :started, :key, CAST(:topics AS uuid[]))
                """)
                .param("id", a.id()).param("student", a.studentId()).param("exam", a.examId())
                .param("title", a.examTitle()).param("slug", a.examSlug()).param("mode", a.mode())
                .param("generation", a.generation()).param("count", a.requestedCount())
                .param("seconds", a.requestedSeconds()).param("bank", a.bank()).param("reason", a.accessReason())
                .param("config", a.configJson()).param("started", a.startedAt()).param("expires", a.expiresAt())
                .param("key", a.idempotencyKey()).param("topics", array(a.topicIds().stream().map(UUID::toString).toList()))
                .update();
        for (NewQuestion q : questions) {
            jdbc.sql("""
                    INSERT INTO attempt_question (attempt_id, position, question_id, question_version_id, topic_id,
                        topic_title, difficulty, est_seconds, answer_type, option_order, correct_labels,
                        misconception_by_label, marks)
                    VALUES (:attempt, :position, :question, :version, :topic, :topicTitle, :difficulty, :est, :type,
                        CAST(:order AS char(1)[]), CAST(:correct AS char(1)[]), CAST(:misconceptions AS jsonb), :marks)
                    """)
                    .param("attempt", a.id()).param("position", q.position()).param("question", q.questionId())
                    .param("version", q.versionId()).param("topic", q.topicId()).param("topicTitle", q.topicTitle())
                    .param("difficulty", q.difficulty()).param("est", q.estSeconds()).param("type", q.answerType())
                    .param("order", array(q.optionOrder())).param("correct", array(q.correctLabels()))
                    .param("misconceptions", q.misconceptionsJson()).param("marks", q.marks())
                    .update();
            jdbc.sql("INSERT INTO response (attempt_id, position) VALUES (:attempt, :position)")
                    .param("attempt", a.id()).param("position", q.position()).update();
        }
    }

    public void saveResponse(UUID attemptId, int position, List<String> selected, boolean flagged, boolean locked,
                             int timeSpentMs, OffsetDateTime answeredAt, Boolean isCorrect, BigDecimal awarded) {
        jdbc.sql("""
                UPDATE response SET selected_labels = CAST(:selected AS char(1)[]), flagged = :flagged, locked = :locked,
                    time_spent_ms = :spent, answered_at = :answeredAt, is_correct = :correct, awarded = :awarded
                WHERE attempt_id = :attempt AND position = :position
                """)
                .param("selected", array(selected)).param("flagged", flagged).param("locked", locked)
                .param("spent", timeSpentMs).param("answeredAt", answeredAt).param("correct", isCorrect)
                .param("awarded", awarded).param("attempt", attemptId).param("position", position)
                .update();
    }

    public void touch(UUID attemptId, OffsetDateTime now) {
        jdbc.sql("UPDATE attempt SET last_activity_at = :now, version = version + 1 WHERE id = :id")
                .param("now", now).param("id", attemptId).update();
    }

    public void finish(UUID attemptId, String reason, OffsetDateTime at, BigDecimal score, BigDecimal max, int correct,
                       int incorrect, int unanswered) {
        jdbc.sql("""
                UPDATE attempt SET status = 'SCORED', finish_reason = :reason, finished_at = :at, score = :score,
                    max_score = :max, correct_count = :correct, incorrect_count = :incorrect,
                    unanswered_count = :unanswered, last_activity_at = :at, version = version + 1
                WHERE id = :id AND status = 'IN_PROGRESS'
                """)
                .param("reason", reason).param("at", at).param("score", score).param("max", max)
                .param("correct", correct).param("incorrect", incorrect).param("unanswered", unanswered)
                .param("id", attemptId)
                .update();
    }

    // --- reads --------------------------------------------------------------------------------------------------------

    public Optional<AttemptRow> find(UUID id) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM attempt a WHERE a.id = :id")
                .param("id", id).query(AttemptRepository::attempt).optional();
    }

    /** Locks the attempt row for the rest of the transaction (answers, submit and the sweep are serialised). */
    public Optional<AttemptRow> lock(UUID id) {
        jdbc.sql("SELECT id FROM attempt WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return find(id);
    }

    public Optional<AttemptRow> byKey(UUID studentId, String key) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM attempt a WHERE a.student_id = :s AND a.idempotency_key = :k")
                .param("s", studentId).param("k", key).query(AttemptRepository::attempt).optional();
    }

    public Optional<UUID> activeMock(UUID studentId, UUID examId) {
        return jdbc.sql("""
                SELECT id FROM attempt
                WHERE student_id = :s AND exam_id = :e AND mode = 'MOCK' AND status = 'IN_PROGRESS'
                """).param("s", studentId).param("e", examId).query(UUID.class).optional();
    }

    public List<QuestionRow> questions(UUID attemptId) {
        return jdbc.sql("""
                SELECT q.*, r.selected_labels, r.flagged, r.locked, r.time_spent_ms, r.answered_at, r.is_correct,
                       r.awarded
                FROM attempt_question q JOIN response r ON r.attempt_id = q.attempt_id AND r.position = q.position
                WHERE q.attempt_id = :id ORDER BY q.position
                """).param("id", attemptId).query(AttemptRepository::question).list();
    }

    /** Questions this student was given in this exam since {@code since}: chosen last next time (SG-4). */
    public Set<UUID> seenSince(UUID studentId, UUID examId, OffsetDateTime since) {
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT q.question_id FROM attempt a JOIN attempt_question q ON q.attempt_id = a.id
                WHERE a.student_id = :s AND a.exam_id = :e AND a.started_at >= :since
                """).param("s", studentId).param("e", examId).param("since", since).query(UUID.class).list());
    }

    public List<AttemptRow> history(UUID studentId, UUID examId, int limit) {
        if (examId == null) {
            return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM attempt a WHERE a.student_id = :s"
                            + " ORDER BY a.started_at DESC LIMIT :limit")
                    .param("s", studentId).param("limit", limit).query(AttemptRepository::attempt).list();
        }
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM attempt a WHERE a.student_id = :s AND a.exam_id = :e"
                        + " ORDER BY a.started_at DESC LIMIT :limit")
                .param("s", studentId).param("e", examId).param("limit", limit)
                .query(AttemptRepository::attempt).list();
    }

    /** Timed attempts past their end (plus grace), and untimed ones idle since before {@code idleBefore}. */
    public List<UUID> due(OffsetDateTime expiredBefore, OffsetDateTime idleBefore, int limit) {
        return jdbc.sql("""
                (SELECT id FROM attempt WHERE status = 'IN_PROGRESS' AND expires_at IS NOT NULL AND expires_at < :expired
                 ORDER BY expires_at LIMIT :limit)
                UNION ALL
                (SELECT id FROM attempt WHERE status = 'IN_PROGRESS' AND expires_at IS NULL AND last_activity_at < :idle
                 ORDER BY last_activity_at LIMIT :limit)
                """).param("expired", expiredBefore).param("idle", idleBefore).param("limit", limit)
                .query(UUID.class).list();
    }

    // --- mapping ------------------------------------------------------------------------------------------------------

    private static AttemptRow attempt(ResultSet rs, int n) throws SQLException {
        return new AttemptRow(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("exam_id", UUID.class), rs.getString("exam_title"), rs.getString("exam_slug"),
                rs.getString("mode"), rs.getString("generation"), (Integer) rs.getObject("requested_count"),
                (Integer) rs.getObject("requested_seconds"), rs.getString("bank"), rs.getString("access_reason"),
                rs.getString("config_snapshot"), rs.getString("status"), rs.getString("finish_reason"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("last_activity_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getBigDecimal("score"), rs.getBigDecimal("max_score"), (Integer) rs.getObject("correct_count"),
                (Integer) rs.getObject("incorrect_count"), (Integer) rs.getObject("unanswered_count"),
                uuids(rs.getArray("topic_ids")), rs.getInt("question_count"));
    }

    private static QuestionRow question(ResultSet rs, int n) throws SQLException {
        return new QuestionRow(rs.getInt("position"), rs.getObject("question_id", UUID.class),
                rs.getObject("question_version_id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("topic_title"), rs.getString("difficulty"), rs.getInt("est_seconds"),
                rs.getString("answer_type"), labels(rs.getArray("option_order")), labels(rs.getArray("correct_labels")),
                rs.getBigDecimal("marks"), labels(rs.getArray("selected_labels")), rs.getBoolean("flagged"),
                rs.getBoolean("locked"), rs.getInt("time_spent_ms"), rs.getObject("answered_at", OffsetDateTime.class),
                (Boolean) rs.getObject("is_correct"), rs.getBigDecimal("awarded"));
    }

    private static List<String> labels(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(v -> String.valueOf(v).strip()).toList();
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(v -> v instanceof UUID u ? u : UUID.fromString(String.valueOf(v)))
                .toList();
    }

    /** A PostgreSQL array literal; values here are labels A–E or UUIDs, which need no quoting. */
    static String array(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }
}
