package com.leenex.exam.assessment.progress;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads a student's marked answers, the raw material of the performance profile (ADR-016). Only answers that have
 * been marked count: checked answers in Practice and Adaptive exams, and Mock answers once the paper is finished, so
 * a running mock exam never reveals anything. Unanswered questions don't count.
 */
@Repository
public class ProgressRepository {

    /** At most this many answers per exam are replayed; older ones no longer move a level. */
    static final int ANSWER_LIMIT = 5000;

    public record Answer(UUID topicId, String topicTitle, UUID questionId, String difficulty, boolean correct,
                         OffsetDateTime answeredAt, UUID attemptId) {
    }

    public record ExamPractised(UUID examId, String title, String slug, OffsetDateTime lastActivity, int attempts) {
    }

    public record Session(UUID attemptId, String mode, OffsetDateTime finishedAt, BigDecimal score, BigDecimal maxScore,
                          int questionCount) {
    }

    private final JdbcClient jdbc;

    public ProgressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Marked answers for one student and exam, oldest first. */
    public List<Answer> answers(UUID studentId, UUID examId) {
        List<Answer> newestFirst = new ArrayList<>(jdbc.sql("""
                SELECT q.topic_id, q.topic_title, q.question_id, q.difficulty, r.is_correct, r.answered_at, a.id
                FROM response r
                JOIN attempt_question q ON q.attempt_id = r.attempt_id AND q.position = r.position
                JOIN attempt a ON a.id = r.attempt_id
                WHERE a.student_id = :s AND a.exam_id = :e AND r.is_correct IS NOT NULL AND r.answered_at IS NOT NULL
                ORDER BY r.answered_at DESC, a.id, q.position DESC
                LIMIT :limit
                """)
                .param("s", studentId).param("e", examId).param("limit", ANSWER_LIMIT)
                .query((rs, n) -> new Answer(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, UUID.class)))
                .list());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    public record Day(java.time.LocalDate day, int answered, int correct) {
    }

    /** Marked answers per day (in {@code zone}) since {@code from}, across all exams. Days without answers are left out. */
    public List<Day> activity(UUID studentId, java.time.ZoneId zone, OffsetDateTime from) {
        return jdbc.sql("""
                SELECT (r.answered_at AT TIME ZONE :zone)::date AS day, count(*) AS answered,
                       count(*) FILTER (WHERE r.is_correct) AS correct
                FROM response r JOIN attempt a ON a.id = r.attempt_id
                WHERE a.student_id = :s AND r.is_correct IS NOT NULL AND r.answered_at >= :from
                GROUP BY 1 ORDER BY 1
                """)
                .param("zone", zone.getId()).param("s", studentId).param("from", from)
                .query((rs, n) -> new Day(rs.getObject(1, java.time.LocalDate.class), rs.getInt(2), rs.getInt(3)))
                .list();
    }

    public record Totals(int answered, int correct, int finishedAttempts) {
    }

    /** Every marked answer and finished attempt of the student, across all exams. */
    public Totals totals(UUID studentId) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM response r JOIN attempt a ON a.id = r.attempt_id
                        WHERE a.student_id = :s AND r.is_correct IS NOT NULL),
                       (SELECT count(*) FROM response r JOIN attempt a ON a.id = r.attempt_id
                        WHERE a.student_id = :s AND r.is_correct),
                       (SELECT count(*) FROM attempt WHERE student_id = :s AND status = 'SCORED')
                """)
                .param("s", studentId)
                .query((rs, n) -> new Totals(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
    }

    /** Exams the student has started, most recent first. */
    public List<ExamPractised> exams(UUID studentId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (exam_id) exam_id, exam_title, exam_slug, last_activity_at,
                       count(*) OVER (PARTITION BY exam_id) AS attempts
                FROM attempt WHERE student_id = :s
                ORDER BY exam_id, started_at DESC
                """)
                .param("s", studentId)
                .query((rs, n) -> new ExamPractised(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getInt(5)))
                .list().stream()
                .sorted((a, b) -> b.lastActivity().compareTo(a.lastActivity()))
                .toList();
    }

    /** Finished attempts on one exam, oldest first (the last {@code limit}). */
    public List<Session> sessions(UUID studentId, UUID examId, int limit) {
        List<Session> newestFirst = new ArrayList<>(jdbc.sql("""
                SELECT a.id, a.mode, a.finished_at, a.score, a.max_score,
                       (SELECT count(*) FROM attempt_question q WHERE q.attempt_id = a.id) AS questions
                FROM attempt a
                WHERE a.student_id = :s AND a.exam_id = :e AND a.status = 'SCORED'
                ORDER BY a.finished_at DESC LIMIT :limit
                """)
                .param("s", studentId).param("e", examId).param("limit", limit)
                .query((rs, n) -> new Session(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6)))
                .list());
        Collections.reverse(newestFirst);
        return newestFirst;
    }
}
