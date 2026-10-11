package com.leenex.exam.identity.user;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for users and roles. Plain SQL: the schema is owned by the Flyway migrations. */
@Repository
public class UserRepository {

    private static final String SELECT = """
            SELECT u.id, u.google_subject, u.email, u.display_name, u.photo_url, u.status, u.preferences::text AS prefs,
                   u.created_at, u.last_login_at, u.version,
                   ARRAY(SELECT r.role FROM user_role r WHERE r.user_id = u.id ORDER BY r.role) AS roles
            FROM app_user u
            """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id").param("id", id).query(UserRepository::map).optional();
    }

    /** Locks the row for the rest of the transaction, so concurrent admin changes can't interleave. */
    public Optional<UserRecord> findByIdForUpdate(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id FOR UPDATE OF u").param("id", id).query(UserRepository::map).optional();
    }

    public Optional<UserRecord> findByGoogleSubject(String subject) {
        return jdbc.sql(SELECT + " WHERE u.google_subject = :sub").param("sub", subject).query(UserRepository::map).optional();
    }

    public void insert(UUID id, String googleSubject, String email, String displayName, String photoUrl) {
        jdbc.sql("""
                INSERT INTO app_user (id, google_subject, email, display_name, photo_url, last_login_at)
                VALUES (:id, :sub, :email, :name, :photo, now())
                """)
                .param("id", id).param("sub", googleSubject).param("email", email)
                .param("name", displayName).param("photo", photoUrl)
                .update();
    }

    /** Whether another account (not {@code exceptId}) already uses this email. Emails are case-insensitive. */
    public boolean emailTaken(String email, UUID exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE email = :email AND id IS DISTINCT FROM :except)")
                .param("email", email).param("except", exceptId).query(Boolean.class).single();
    }

    /** Keeps email and photo in step with the Google account; the display name is the user's own choice. */
    public void recordLogin(UUID id, String email, String photoUrl) {
        jdbc.sql("""
                UPDATE app_user SET email = :email, photo_url = :photo, last_login_at = now(), updated_at = now()
                WHERE id = :id
                """)
                .param("id", id).param("email", email).param("photo", photoUrl)
                .update();
    }

    /** Returns false when the row changed since {@code expectedVersion} was read. */
    public boolean updateProfile(UUID id, int expectedVersion, String displayName, String preferencesJson) {
        return jdbc.sql("""
                UPDATE app_user
                SET display_name = COALESCE(:name, display_name),
                    preferences  = COALESCE(CAST(:prefs AS jsonb), preferences),
                    version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version AND status = 'ACTIVE'
                """)
                .param("id", id).param("version", expectedVersion)
                .param("name", displayName).param("prefs", preferencesJson)
                .update() == 1;
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app_user SET status = :status, version = version + 1, updated_at = now() WHERE id = :id")
                .param("id", id).param("status", status).update();
    }

    /** Removes personal data but keeps the row, so other services' statistics stay consistent (FR-ID-09). */
    public void anonymise(UUID id) {
        jdbc.sql("""
                UPDATE app_user
                SET status = 'DELETED', google_subject = 'deleted:' || id, email = 'deleted+' || id || '@invalid',
                    display_name = 'Deleted user', photo_url = NULL, preferences = '{}'::jsonb,
                    deleted_at = now(), updated_at = now(), version = version + 1
                WHERE id = :id
                """)
                .param("id", id).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", id).update();
    }

    public void addRole(UUID userId, String role, UUID grantedBy) {
        jdbc.sql("""
                INSERT INTO user_role (user_id, role, granted_by) VALUES (:user, :role, :by)
                ON CONFLICT (user_id, role) DO NOTHING
                """)
                .param("user", userId).param("role", role).param("by", grantedBy).update();
    }

    public boolean removeRole(UUID userId, String role) {
        return jdbc.sql("DELETE FROM user_role WHERE user_id = :user AND role = :role")
                .param("user", userId).param("role", role).update() == 1;
    }

    /** Administrators who can still sign in. */
    public int countActiveAdmins() {
        return jdbc.sql("""
                SELECT count(*) FROM user_role r JOIN app_user u ON u.id = r.user_id
                WHERE r.role = 'ADMIN' AND u.status = 'ACTIVE'
                """).query(Integer.class).single();
    }

    /** Records the one-time admin bootstrap; true only for the first call ever. */
    public boolean claimAdminBootstrap(String email) {
        return jdbc.sql("INSERT INTO admin_bootstrap (email) VALUES (:email) ON CONFLICT (id) DO NOTHING")
                .param("email", email).update() == 1;
    }

    public record Page(List<UserRecord> items, long total) {
    }

    public Page search(String query, String role, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE u.status <> 'DELETED'");
        Map<String, Object> params = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND (u.display_name ILIKE :q ESCAPE '\\' OR u.email::text ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (role != null) {
            where.append(" AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role = :role)");
            params.put("role", role);
        }
        if (status != null) {
            where.append(" AND u.status = :status");
            params.put("status", status);
        }
        long total = jdbc.sql("SELECT count(*) FROM app_user u" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<UserRecord> items = jdbc.sql(SELECT + where + " ORDER BY u.created_at DESC, u.id LIMIT :limit OFFSET :offset")
                .params(params).query(UserRepository::map).list();
        return new Page(items, total);
    }

    private static UserRecord map(ResultSet rs, int row) throws SQLException {
        Array roles = rs.getArray("roles");
        List<String> roleList = roles == null ? List.of()
                : new ArrayList<>(Arrays.asList((String[]) roles.getArray()));
        return new UserRecord(
                rs.getObject("id", UUID.class),
                rs.getString("google_subject"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("photo_url"),
                rs.getString("status"),
                rs.getString("prefs"),
                List.copyOf(roleList),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_login_at", OffsetDateTime.class),
                rs.getInt("version"));
    }
}
