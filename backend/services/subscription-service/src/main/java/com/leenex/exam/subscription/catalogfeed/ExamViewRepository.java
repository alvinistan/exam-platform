package com.leenex.exam.subscription.catalogfeed;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** This service's read model of the catalogue (billing.exam_view), kept up to date from catalog events. */
@Repository
public class ExamViewRepository {

    public record ExamView(UUID examId, UUID categoryId, String title, String slug, Integer displayOrder, String status,
                           OffsetDateTime publishedAt, List<String> modes, Map<String, Integer> freeAttempts) {

        public boolean published() {
            return "PUBLISHED".equals(status);
        }
    }

    private static final String COLUMNS =
            "exam_id, category_id, title, slug, display_order, status, published_at, modes, free_attempts";
    private static final TypeReference<LinkedHashMap<String, Integer>> COUNTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ExamViewRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsert(UUID examId, UUID categoryId, String title, String slug, Integer displayOrder, String status,
                       OffsetDateTime occurredAt, List<String> modes, Map<String, Integer> freeAttempts) {
        jdbc.sql("""
                INSERT INTO exam_view (exam_id, category_id, title, slug, display_order, status, published_at, updated_at,
                                       modes, free_attempts)
                VALUES (:id, :category, :title, :slug, :order, :status,
                        CASE WHEN :status = 'PUBLISHED' THEN CAST(:at AS timestamptz) END, now(),
                        CAST(:modes AS text[]), CAST(:free AS jsonb))
                ON CONFLICT (exam_id) DO UPDATE SET
                    category_id = EXCLUDED.category_id, title = EXCLUDED.title, slug = EXCLUDED.slug,
                    display_order = EXCLUDED.display_order, status = EXCLUDED.status,
                    published_at = CASE WHEN EXCLUDED.status = 'PUBLISHED'
                                        THEN coalesce(exam_view.published_at, EXCLUDED.published_at) END,
                    modes = EXCLUDED.modes, free_attempts = EXCLUDED.free_attempts,
                    updated_at = now()
                """)
                .param("id", examId).param("category", categoryId).param("title", title == null ? "" : title)
                .param("slug", slug == null ? "" : slug).param("order", displayOrder).param("status", status)
                .param("at", occurredAt).param("modes", "{" + String.join(",", modes) + "}")
                .param("free", json.writeValueAsString(freeAttempts))
                .update();
    }

    /** Published exams in catalogue order: position, then first publication, then ID (a stable tie-break). */
    public List<ExamView> publishedInOrder() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM exam_view WHERE status = 'PUBLISHED' "
                        + "ORDER BY display_order NULLS LAST, published_at NULLS LAST, exam_id")
                .query(this::row).list();
    }

    public List<ExamView> find(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM exam_view WHERE exam_id IN (:ids)")
                .param("ids", ids).query(this::row).list();
    }

    private ExamView row(ResultSet rs, int n) throws SQLException {
        int order = rs.getInt("display_order");
        Integer displayOrder = rs.wasNull() ? null : order;
        Array modes = rs.getArray("modes");
        return new ExamView(rs.getObject("exam_id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("title"), rs.getString("slug"), displayOrder, rs.getString("status"),
                rs.getObject("published_at", OffsetDateTime.class),
                modes == null ? List.of() : List.of((String[]) modes.getArray()),
                json.readValue(rs.getString("free_attempts"), COUNTS));
    }
}
