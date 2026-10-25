package com.leenex.exam.catalog.content;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access for the reusable content tree: subject → module → topic (FR-CAT-03). Subjects are linked to exams,
 * so one subject (and its question bank) can serve several exams without copying.
 */
@Repository
public class ContentRepository {

    public record SubjectRow(UUID id, String title, String code, String description, String status, int version,
                             int linkedExamCount) {
    }

    public record ModuleRow(UUID id, UUID subjectId, String title, int displayOrder, String status, int version) {
    }

    public record TopicRow(UUID id, UUID moduleId, String title, int displayOrder, String status, int version,
                           List<UUID> prerequisiteTopicIds, int publishedMain, int publishedTrial) {
    }

    private final JdbcClient jdbc;

    public ContentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- subjects ---------------------------------------------------------------------------------------------------

    private static final String SUBJECT_SELECT = """
            SELECT s.id, s.title, s.code, s.description, s.status, s.version,
                   (SELECT count(*) FROM catalog.exam_subject es JOIN catalog.exam e ON e.id = es.exam_id
                    WHERE es.subject_id = s.id AND e.status <> 'ARCHIVED') AS linked
            FROM catalog.subject s
            """;

    public List<SubjectRow> subjects() {
        return jdbc.sql(SUBJECT_SELECT + " ORDER BY s.title").query(ContentRepository::subject).list();
    }

    public Optional<SubjectRow> subject(UUID id) {
        return jdbc.sql(SUBJECT_SELECT + " WHERE s.id = :id").param("id", id).query(ContentRepository::subject).optional();
    }

    public Optional<SubjectRow> lockSubject(UUID id) {
        jdbc.sql("SELECT 1 FROM catalog.subject WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return subject(id);
    }

    public boolean subjectCodeTaken(String code, UUID exceptId) {
        return code != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.subject WHERE code = :code AND id IS DISTINCT FROM :except)")
                .param("code", code).param("except", exceptId).query(Boolean.class).single();
    }

    public void insertSubject(UUID id, String title, String code, String description) {
        jdbc.sql("INSERT INTO catalog.subject (id, title, code, description) VALUES (:id, :title, :code, :description)")
                .param("id", id).param("title", title).param("code", code).param("description", description).update();
    }

    public boolean updateSubject(UUID id, int version, String title, String code, String description, String status) {
        return jdbc.sql("""
                UPDATE catalog.subject SET title = :title, code = :code, description = :description, status = :status,
                       version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", version).param("title", title).param("code", code)
                .param("description", description).param("status", status).update() == 1;
    }

    // --- modules ----------------------------------------------------------------------------------------------------

    private static final String MODULE_SELECT = "SELECT id, subject_id, title, display_order, status, version FROM catalog.module";

    public List<ModuleRow> modules() {
        return jdbc.sql(MODULE_SELECT + " ORDER BY display_order, title").query(ContentRepository::module).list();
    }

    public Optional<ModuleRow> module(UUID id) {
        return jdbc.sql(MODULE_SELECT + " WHERE id = :id").param("id", id).query(ContentRepository::module).optional();
    }

    public Optional<ModuleRow> lockModule(UUID id) {
        return jdbc.sql(MODULE_SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query(ContentRepository::module).optional();
    }

    public void insertModule(UUID id, UUID subjectId, String title, int displayOrder) {
        jdbc.sql("INSERT INTO catalog.module (id, subject_id, title, display_order) VALUES (:id, :subject, :title, :order)")
                .param("id", id).param("subject", subjectId).param("title", title).param("order", displayOrder).update();
    }

    public boolean updateModule(UUID id, int version, String title, int displayOrder, String status) {
        return jdbc.sql("""
                UPDATE catalog.module SET title = :title, display_order = :order, status = :status, version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", version).param("title", title).param("order", displayOrder)
                .param("status", status).update() == 1;
    }

    // --- topics -----------------------------------------------------------------------------------------------------

    private static final String TOPIC_SELECT = """
            SELECT t.id, t.module_id, t.title, t.display_order, t.status, t.version,
                   ARRAY(SELECT p.prerequisite_topic_id FROM catalog.topic_prerequisite p WHERE p.topic_id = t.id
                         ORDER BY p.prerequisite_topic_id) AS prereqs,
                   (SELECT count(*) FROM bank_main.question q WHERE q.topic_id = t.id AND q.status = 'PUBLISHED') AS main,
                   (SELECT count(*) FROM bank_trial.question q WHERE q.topic_id = t.id AND q.status = 'PUBLISHED') AS trial
            FROM catalog.topic t
            """;

    public List<TopicRow> topics() {
        return jdbc.sql(TOPIC_SELECT + " ORDER BY t.display_order, t.title").query(ContentRepository::topic).list();
    }

    public Optional<TopicRow> topic(UUID id) {
        return jdbc.sql(TOPIC_SELECT + " WHERE t.id = :id").param("id", id).query(ContentRepository::topic).optional();
    }

    public Optional<TopicRow> lockTopic(UUID id) {
        jdbc.sql("SELECT 1 FROM catalog.topic WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
        return topic(id);
    }

    public int countTopics(Collection<UUID> ids) {
        return ids.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM catalog.topic WHERE id IN (:ids)")
                .param("ids", ids).query(Integer.class).single();
    }

    public void insertTopic(UUID id, UUID moduleId, String title, int displayOrder) {
        jdbc.sql("INSERT INTO catalog.topic (id, module_id, title, display_order) VALUES (:id, :module, :title, :order)")
                .param("id", id).param("module", moduleId).param("title", title).param("order", displayOrder).update();
    }

    public boolean updateTopic(UUID id, int version, String title, int displayOrder, String status) {
        return jdbc.sql("""
                UPDATE catalog.topic SET title = :title, display_order = :order, status = :status, version = version + 1
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", version).param("title", title).param("order", displayOrder)
                .param("status", status).update() == 1;
    }

    public void replacePrerequisites(UUID topicId, Collection<UUID> prerequisites) {
        jdbc.sql("DELETE FROM catalog.topic_prerequisite WHERE topic_id = :id").param("id", topicId).update();
        for (UUID prerequisite : prerequisites) {
            jdbc.sql("INSERT INTO catalog.topic_prerequisite (topic_id, prerequisite_topic_id) VALUES (:t, :p)")
                    .param("t", topicId).param("p", prerequisite).update();
        }
    }

    /** True when {@code topicId} is already a (direct or indirect) prerequisite of {@code from}: adding the edge would loop. */
    public boolean reachable(UUID from, UUID topicId) {
        return jdbc.sql("""
                WITH RECURSIVE chain(id) AS (
                    SELECT CAST(:from AS uuid)
                    UNION
                    SELECT p.prerequisite_topic_id FROM catalog.topic_prerequisite p JOIN chain c ON p.topic_id = c.id
                )
                SELECT EXISTS (SELECT 1 FROM chain WHERE id = :target)
                """)
                .param("from", from).param("target", topicId).query(Boolean.class).single();
    }

    // --- exams affected by a change -----------------------------------------------------------------------------------

    public List<UUID> publishedExamsUsingSubject(UUID subjectId) {
        return jdbc.sql("""
                SELECT e.id FROM catalog.exam e JOIN catalog.exam_subject es ON es.exam_id = e.id
                WHERE es.subject_id = :id AND e.status = 'PUBLISHED'
                """).param("id", subjectId).query(UUID.class).list();
    }

    private static SubjectRow subject(ResultSet rs, int row) throws SQLException {
        return new SubjectRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("code"),
                rs.getString("description"), rs.getString("status"), rs.getInt("version"), rs.getInt("linked"));
    }

    private static ModuleRow module(ResultSet rs, int row) throws SQLException {
        return new ModuleRow(rs.getObject("id", UUID.class), rs.getObject("subject_id", UUID.class), rs.getString("title"),
                rs.getInt("display_order"), rs.getString("status"), rs.getInt("version"));
    }

    private static TopicRow topic(ResultSet rs, int row) throws SQLException {
        Array prereqs = rs.getArray("prereqs");
        List<UUID> ids = prereqs == null ? List.of() : Arrays.stream((Object[]) prereqs.getArray()).map(o -> (UUID) o).toList();
        return new TopicRow(rs.getObject("id", UUID.class), rs.getObject("module_id", UUID.class), rs.getString("title"),
                rs.getInt("display_order"), rs.getString("status"), rs.getInt("version"), ids, rs.getInt("main"),
                rs.getInt("trial"));
    }
}
