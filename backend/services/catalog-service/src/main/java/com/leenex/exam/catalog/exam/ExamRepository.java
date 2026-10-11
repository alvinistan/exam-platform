package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.support.Requests;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for exam definitions, their subject links and slug history (FR-CAT-02, -03, -06). */
@Repository
public class ExamRepository {

    private static final String SELECT = """
            SELECT e.id, e.category_id, e.title, e.slug, e.short_description, e.instructions, e.seo_title,
                   e.seo_description, e.display_order, e.featured, e.status, e.published_at, e.updated_at, e.version
            FROM catalog.exam e
            """;

    /** The active topics an exam draws questions from: linked active subjects → active modules → active topics. */
    static final String EXAM_TOPICS = """
            SELECT t.id FROM catalog.exam_subject es
            JOIN catalog.subject s ON s.id = es.subject_id AND s.status = 'ACTIVE'
            JOIN catalog.module m ON m.subject_id = s.id AND m.status = 'ACTIVE'
            JOIN catalog.topic t ON t.module_id = m.id AND t.status = 'ACTIVE'
            WHERE es.exam_id = :id
            """;

    private final JdbcClient jdbc;

    public ExamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExamRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id").param("id", id).query(ExamRepository::map).optional();
    }

    /** Locks the exam row for the rest of the transaction, so concurrent admin changes can't interleave. */
    public Optional<ExamRow> lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id FOR UPDATE").param("id", id).query(ExamRepository::map).optional();
    }

    public Optional<ExamRow> findBySlug(String slug) {
        return jdbc.sql(SELECT + " WHERE e.slug = :slug").param("slug", slug).query(ExamRepository::map).optional();
    }

    /** The exam that used to have this slug, if it was renamed (FR-CAT-06). */
    public Optional<UUID> formerOwnerOfSlug(String slug) {
        return jdbc.sql("SELECT exam_id FROM catalog.exam_slug_history WHERE slug = :slug")
                .param("slug", slug).query(UUID.class).optional();
    }

    /** A slug is taken when another exam uses it now or used it before; an exam may move back to its own old slug. */
    public boolean slugTaken(String slug, UUID exceptExamId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM catalog.exam WHERE slug = :slug AND id IS DISTINCT FROM :except)
                    OR EXISTS (SELECT 1 FROM catalog.exam_slug_history WHERE slug = :slug AND exam_id IS DISTINCT FROM :except)
                """)
                .param("slug", slug).param("except", exceptExamId).query(Boolean.class).single();
    }

    public record ExamFields(UUID categoryId, String title, String slug, String shortDescription, String instructions,
                             String seoTitle, String seoDescription, Integer displayOrder, boolean featured) {
    }

    public void insert(UUID id, ExamFields f, UUID actorId) {
        jdbc.sql("""
                INSERT INTO catalog.exam (id, category_id, title, slug, short_description, instructions, seo_title,
                                          seo_description, display_order, featured, created_by, updated_by)
                VALUES (:id, :category, :title, :slug, :short, :instructions, :seoTitle, :seoDescription, :order,
                        :featured, :actor, :actor)
                """)
                .params(fieldParams(f)).param("id", id).param("actor", actorId)
                .update();
    }

    public void update(UUID id, ExamFields f, UUID actorId) {
        jdbc.sql("""
                UPDATE catalog.exam
                SET category_id = :category, title = :title, slug = :slug, short_description = :short,
                    instructions = :instructions, seo_title = :seoTitle, seo_description = :seoDescription,
                    display_order = :order, featured = :featured, updated_by = :actor, updated_at = now(),
                    version = version + 1
                WHERE id = :id
                """)
                .params(fieldParams(f)).param("id", id).param("actor", actorId)
                .update();
    }

    public void setStatus(UUID id, String status, Integer displayOrder, UUID actorId) {
        jdbc.sql("""
                UPDATE catalog.exam
                SET status = :status, display_order = :order, updated_by = :actor, updated_at = now(), version = version + 1,
                    published_at = CASE WHEN :status = 'PUBLISHED' THEN now() ELSE published_at END
                WHERE id = :id
                """)
                .param("id", id).param("status", status).param("order", displayOrder).param("actor", actorId)
                .update();
    }

    /** Bumps the version after a change to the exam's subjects or modes, so open editors see it changed. */
    public void touch(UUID id, UUID actorId) {
        jdbc.sql("UPDATE catalog.exam SET updated_by = :actor, updated_at = now(), version = version + 1 WHERE id = :id")
                .param("id", id).param("actor", actorId).update();
    }

    public List<UUID> subjectIds(UUID examId) {
        return jdbc.sql("SELECT subject_id FROM catalog.exam_subject WHERE exam_id = :id ORDER BY display_order")
                .param("id", examId).query(UUID.class).list();
    }

    public void replaceSubjects(UUID examId, List<UUID> subjectIds) {
        jdbc.sql("DELETE FROM catalog.exam_subject WHERE exam_id = :id").param("id", examId).update();
        for (int i = 0; i < subjectIds.size(); i++) {
            jdbc.sql("INSERT INTO catalog.exam_subject (exam_id, subject_id, display_order) VALUES (:exam, :subject, :order)")
                    .param("exam", examId).param("subject", subjectIds.get(i)).param("order", i).update();
        }
    }

    public int countActiveSubjects(List<UUID> ids) {
        return ids.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM catalog.subject WHERE id IN (:ids) AND status = 'ACTIVE'")
                .param("ids", ids).query(Integer.class).single();
    }

    /** The published exam (other than {@code exceptId}) holding a catalogue position, if any. */
    public Optional<ExamRow> publishedAtPosition(int displayOrder, UUID exceptId) {
        return jdbc.sql(SELECT + " WHERE e.status = 'PUBLISHED' AND e.display_order = :order AND e.id <> :except")
                .param("order", displayOrder).param("except", exceptId).query(ExamRepository::map).optional();
    }

    public int nextDisplayOrder() {
        return jdbc.sql("SELECT COALESCE(max(display_order), 0) + 1 FROM catalog.exam WHERE status = 'PUBLISHED'")
                .query(Integer.class).single();
    }

    public record ContentCounts(int topics, int publishedMain, int publishedTrial) {
    }

    /** Active topics and published questions per bank across the exam's content tree (FR-CAT-05). */
    public ContentCounts counts(UUID examId) {
        return jdbc.sql("WITH topics AS (" + EXAM_TOPICS + """
                )
                SELECT (SELECT count(*) FROM topics) AS topics,
                       (SELECT count(*) FROM bank_main.question q
                        WHERE q.status = 'PUBLISHED' AND q.topic_id IN (SELECT id FROM topics)) AS main,
                       (SELECT count(*) FROM bank_trial.question q
                        WHERE q.status = 'PUBLISHED' AND q.topic_id IN (SELECT id FROM topics)) AS trial
                """)
                .param("id", examId)
                .query((rs, row) -> new ContentCounts(rs.getInt("topics"), rs.getInt("main"), rs.getInt("trial")))
                .single();
    }

    public record SearchResult(List<ExamRow> items, long total) {
    }

    public SearchResult adminSearch(String q, String status, UUID categoryId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE true");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (e.title ILIKE :q ESCAPE '\\' OR e.slug ILIKE :q ESCAPE '\\')");
            params.put("q", Requests.containsPattern(q));
        }
        if (status != null) {
            where.append(" AND e.status = :status");
            params.put("status", status);
        }
        if (categoryId != null) {
            where.append(" AND e.category_id = :category");
            params.put("category", categoryId);
        }
        return page(where, params, " ORDER BY e.updated_at DESC, e.id", page, size);
    }

    /** Published exams only, in catalogue order (FR-CAT-04, FR-CAT-11). */
    public SearchResult publicSearch(String q, String categorySlug, Boolean featured, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE e.status = 'PUBLISHED'");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (e.title ILIKE :q ESCAPE '\\' OR e.short_description ILIKE :q ESCAPE '\\')");
            params.put("q", Requests.containsPattern(q));
        }
        if (categorySlug != null && !categorySlug.isBlank()) {
            where.append(" AND e.category_id = (SELECT c.id FROM catalog.exam_category c WHERE c.slug = :category)");
            params.put("category", categorySlug);
        }
        if (featured != null) {
            where.append(" AND e.featured = :featured");
            params.put("featured", featured);
        }
        return page(where, params, " ORDER BY e.display_order NULLS LAST, e.title, e.id", page, size);
    }

    private SearchResult page(StringBuilder where, Map<String, Object> params, String order, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM catalog.exam e" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<ExamRow> items = jdbc.sql(SELECT + where + order + " LIMIT :limit OFFSET :offset")
                .params(params).query(ExamRepository::map).list();
        return new SearchResult(items, total);
    }

    // --- public content tree (FR-CAT-09) --------------------------------------------------------------------------------

    public record TreeRow(UUID subjectId, String subjectTitle, UUID moduleId, String moduleTitle, UUID topicId,
                          String topicTitle, int topicOrder) {
    }

    /** Active subjects → modules → topics linked to the exam, in display order; empty modules are left out. */
    public List<TreeRow> activeTree(UUID examId) {
        return jdbc.sql("""
                SELECT s.id AS sid, s.title AS stitle, m.id AS mid, m.title AS mtitle, t.id AS tid, t.title AS ttitle,
                       t.display_order AS torder
                FROM catalog.exam_subject es
                JOIN catalog.subject s ON s.id = es.subject_id AND s.status = 'ACTIVE'
                JOIN catalog.module m ON m.subject_id = s.id AND m.status = 'ACTIVE'
                JOIN catalog.topic t ON t.module_id = m.id AND t.status = 'ACTIVE'
                WHERE es.exam_id = :id
                ORDER BY es.display_order, m.display_order, m.title, t.display_order, t.title
                """)
                .param("id", examId)
                .query((rs, row) -> new TreeRow(rs.getObject("sid", UUID.class), rs.getString("stitle"),
                        rs.getObject("mid", UUID.class), rs.getString("mtitle"), rs.getObject("tid", UUID.class),
                        rs.getString("ttitle"), rs.getInt("torder")))
                .list();
    }

    private static Map<String, Object> fieldParams(ExamFields f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("category", f.categoryId());
        p.put("title", f.title());
        p.put("slug", f.slug());
        p.put("short", f.shortDescription());
        p.put("instructions", f.instructions());
        p.put("seoTitle", f.seoTitle());
        p.put("seoDescription", f.seoDescription());
        p.put("order", f.displayOrder());
        p.put("featured", f.featured());
        return p;
    }

    private static ExamRow map(ResultSet rs, int row) throws SQLException {
        return new ExamRow(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("title"),
                rs.getString("slug"), rs.getString("short_description"), rs.getString("instructions"),
                rs.getString("seo_title"), rs.getString("seo_description"), (Integer) rs.getObject("display_order"),
                rs.getBoolean("featured"), rs.getString("status"), rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("version"));
    }
}
