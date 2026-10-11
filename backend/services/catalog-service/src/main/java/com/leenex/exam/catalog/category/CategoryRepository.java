package com.leenex.exam.catalog.category;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for exam categories (FR-CAT-01). */
@Repository
public class CategoryRepository {

    public record CategoryRow(UUID id, String name, String slug, String description, int displayOrder, String status,
                              int version, int publishedExamCount, int examCount) {
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.slug, c.description, c.display_order, c.status, c.version,
                   (SELECT count(*) FROM catalog.exam e WHERE e.category_id = c.id AND e.status = 'PUBLISHED') AS published,
                   (SELECT count(*) FROM catalog.exam e WHERE e.category_id = c.id AND e.status <> 'ARCHIVED') AS exams
            FROM catalog.exam_category c
            """;

    private final JdbcClient jdbc;

    public CategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CategoryRow> findAll(boolean activeOnly) {
        String where = activeOnly ? " WHERE c.status = 'ACTIVE'" : "";
        return jdbc.sql(SELECT + where + " ORDER BY c.display_order, c.name").query(CategoryRepository::map).list();
    }

    public Optional<CategoryRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(CategoryRepository::map).optional();
    }

    public void lock(UUID id) {
        jdbc.sql("SELECT 1 FROM catalog.exam_category WHERE id = :id FOR UPDATE").param("id", id).query(Integer.class).optional();
    }

    public boolean slugTaken(String slug, UUID exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.exam_category WHERE slug = :slug AND id IS DISTINCT FROM :except)")
                .param("slug", slug).param("except", exceptId).query(Boolean.class).single();
    }

    public void insert(UUID id, String name, String slug, String description, int displayOrder) {
        jdbc.sql("""
                INSERT INTO catalog.exam_category (id, name, slug, description, display_order)
                VALUES (:id, :name, :slug, :description, :order)
                """)
                .param("id", id).param("name", name).param("slug", slug).param("description", description)
                .param("order", displayOrder)
                .update();
    }

    /** Returns false when the row changed since {@code expectedVersion} was read. */
    public boolean update(UUID id, int expectedVersion, String name, String slug, String description, int displayOrder) {
        return jdbc.sql("""
                UPDATE catalog.exam_category
                SET name = :name, slug = :slug, description = :description, display_order = :order,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version
                """)
                .param("id", id).param("version", expectedVersion).param("name", name).param("slug", slug)
                .param("description", description).param("order", displayOrder)
                .update() == 1;
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE catalog.exam_category SET status = :status, version = version + 1, updated_at = now() WHERE id = :id")
                .param("id", id).param("status", status).update();
    }

    private static CategoryRow map(ResultSet rs, int row) throws SQLException {
        return new CategoryRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getInt("display_order"), rs.getString("status"), rs.getInt("version"),
                rs.getInt("published"), rs.getInt("exams"));
    }
}
