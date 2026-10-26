package com.leenex.exam.subscription.catalogfeed;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** This service's read model of the catalogue (billing.exam_view), kept up to date from catalog events. */
@Repository
public class ExamViewRepository {

    public record ExamView(UUID examId, UUID categoryId, String title, String slug, Integer displayOrder, String status,
                           OffsetDateTime publishedAt) {
    }

    private static final String COLUMNS = "exam_id, category_id, title, slug, display_order, status, published_at";

    private final JdbcClient jdbc;

    public ExamViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(UUID examId, UUID categoryId, String title, String slug, Integer displayOrder, String status,
                       OffsetDateTime occurredAt) {
        jdbc.sql("""
                INSERT INTO exam_view (exam_id, category_id, title, slug, display_order, status, published_at, updated_at)
                VALUES (:id, :category, :title, :slug, :order, :status,
                        CASE WHEN :status = 'PUBLISHED' THEN CAST(:at AS timestamptz) END, now())
                ON CONFLICT (exam_id) DO UPDATE SET
                    category_id = EXCLUDED.category_id, title = EXCLUDED.title, slug = EXCLUDED.slug,
                    display_order = EXCLUDED.display_order, status = EXCLUDED.status,
                    published_at = CASE WHEN EXCLUDED.status = 'PUBLISHED'
                                        THEN coalesce(exam_view.published_at, EXCLUDED.published_at) END,
                    updated_at = now()
                """)
                .param("id", examId).param("category", categoryId).param("title", title == null ? "" : title)
                .param("slug", slug == null ? "" : slug).param("order", displayOrder).param("status", status)
                .param("at", occurredAt)
                .update();
    }

    /** Published exams in catalogue order: position, then first publication, then ID (a stable tie-break). */
    public List<ExamView> publishedInOrder() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM exam_view WHERE status = 'PUBLISHED' "
                        + "ORDER BY display_order NULLS LAST, published_at NULLS LAST, exam_id")
                .query(ExamViewRepository::row).list();
    }

    public List<ExamView> find(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM exam_view WHERE exam_id IN (:ids)")
                .param("ids", ids).query(ExamViewRepository::row).list();
    }

    private static ExamView row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int order = rs.getInt("display_order");
        return new ExamView(rs.getObject("exam_id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("title"), rs.getString("slug"), rs.wasNull() ? null : order, rs.getString("status"),
                rs.getObject("published_at", OffsetDateTime.class));
    }
}
