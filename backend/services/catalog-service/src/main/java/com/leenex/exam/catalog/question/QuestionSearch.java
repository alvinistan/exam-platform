package com.leenex.exam.catalog.question;

import com.leenex.exam.catalog.support.Requests;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The admin question list (FR-QB-07): text, tag and content filters across one or both banks, sorted and paged. */
@Repository
public class QuestionSearch {

    public record Filters(String q, String tag, Bank bank, UUID examId, UUID subjectId, UUID moduleId, UUID topicId,
                          String status, String difficulty, String sort) {
    }

    public record Summary(UUID id, Bank bank, String status, UUID topicId, String topicTitle, String moduleTitle,
                          String subjectTitle, String difficulty, String answerType, String stemText, int versionNo,
                          boolean hasDraftChanges, List<String> tags, OffsetDateTime updatedAt, UUID updatedBy) {
    }

    public record Result(List<Summary> items, long total) {
    }

    private final JdbcClient jdbc;

    public QuestionSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Result search(Filters f, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND v.stem_text ILIKE :q ESCAPE '\\'");
            params.put("q", Requests.containsPattern(f.q()));
        }
        if (f.examId() != null) {
            where.append(" AND s.id IN (SELECT subject_id FROM catalog.exam_subject WHERE exam_id = :exam)");
            params.put("exam", f.examId());
        }
        if (f.subjectId() != null) {
            where.append(" AND s.id = :subject");
            params.put("subject", f.subjectId());
        }
        if (f.moduleId() != null) {
            where.append(" AND m.id = :module");
            params.put("module", f.moduleId());
        }
        if (f.topicId() != null) {
            where.append(" AND t.id = :topic");
            params.put("topic", f.topicId());
        }
        if (f.status() != null) {
            where.append(" AND q.status = :status");
            params.put("status", f.status());
        }
        if (f.difficulty() != null) {
            where.append(" AND v.difficulty = :difficulty");
            params.put("difficulty", f.difficulty());
        }
        String tagFilter = "";
        if (f.tag() != null && !f.tag().isBlank()) {
            tagFilter = " AND EXISTS (SELECT 1 FROM %s qt WHERE qt.question_id = q.id AND qt.tag = :tag)";
            params.put("tag", f.tag().strip().toLowerCase());
        }
        StringBuilder union = new StringBuilder();
        for (Bank bank : Bank.values()) {
            if (f.bank() != null && f.bank() != bank) {
                continue;
            }
            if (!union.isEmpty()) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT q.id, '").append(bank.name()).append("' AS bank, q.status, q.topic_id, t.title AS topic_title,")
                    .append(" m.title AS module_title, s.title AS subject_title, v.difficulty, v.answer_type,")
                    .append(" left(v.stem_text, 240) AS stem_text, q.current_version_no,")
                    .append(" (v.frozen_at IS NULL AND EXISTS (SELECT 1 FROM ").append(bank.table("question_version"))
                    .append(" fv WHERE fv.question_id = q.id AND fv.frozen_at IS NOT NULL)) AS draft_changes,")
                    .append(" ARRAY(SELECT tag FROM ").append(bank.table("question_tag"))
                    .append(" x WHERE x.question_id = q.id ORDER BY tag) AS tags, q.updated_at, q.updated_by")
                    .append(" FROM ").append(bank.table("question")).append(" q")
                    .append(" JOIN ").append(bank.table("question_version"))
                    .append(" v ON v.question_id = q.id AND v.version_no = q.current_version_no")
                    .append(" JOIN catalog.topic t ON t.id = q.topic_id JOIN catalog.module m ON m.id = t.module_id")
                    .append(" JOIN catalog.subject s ON s.id = m.subject_id")
                    .append(where).append(tagFilter.formatted(bank.table("question_tag")));
        }
        String order = switch (f.sort() == null ? "-updatedAt" : f.sort()) {
            case "updatedAt" -> " ORDER BY updated_at, id";
            case "difficulty" -> " ORDER BY array_position(ARRAY['SIMPLE','MEDIUM','HARD'], difficulty), updated_at DESC, id";
            default -> " ORDER BY updated_at DESC, id";
        };
        long total = jdbc.sql("SELECT count(*) FROM (" + union + ") x").params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Summary> items = jdbc.sql("SELECT * FROM (" + union + ") x" + order + " LIMIT :limit OFFSET :offset")
                .params(params).query(QuestionSearch::map).list();
        return new Result(items, total);
    }

    private static Summary map(ResultSet rs, int row) throws SQLException {
        Array tags = rs.getArray("tags");
        return new Summary(rs.getObject("id", UUID.class), Bank.valueOf(rs.getString("bank")), rs.getString("status"),
                rs.getObject("topic_id", UUID.class), rs.getString("topic_title"), rs.getString("module_title"),
                rs.getString("subject_title"), rs.getString("difficulty"), rs.getString("answer_type"),
                rs.getString("stem_text"), rs.getInt("current_version_no"), rs.getBoolean("draft_changes"),
                tags == null ? List.of() : Arrays.asList((String[]) tags.getArray()),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("updated_by", UUID.class));
    }
}
