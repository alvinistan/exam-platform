package com.leenex.exam.identity.token;

import com.leenex.exam.identity.config.IdentityProperties;
import com.leenex.exam.platform.web.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rotating refresh tokens (ADR-004). The client gets 256 random bits; only their SHA-256 hash is stored.
 * Each use replaces the token with a new one in the same family. Presenting an already-replaced token means it
 * was copied, so the whole family (every device session that grew from that sign-in) is revoked, unless it
 * happened within a few seconds of the rotation, which is just two browser tabs refreshing at the same time.
 */
@Component
public class RefreshTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Duration reuseGrace;

    public RefreshTokens(JdbcClient jdbc, IdentityProperties properties) {
        this.jdbc = jdbc;
        this.ttl = properties.refresh().ttl();
        this.reuseGrace = properties.refresh().reuseGrace();
    }

    public Duration ttl() {
        return ttl;
    }

    public enum ClientType { WEB, MOBILE }

    /** A newly issued token: the raw value goes to the client once and is never stored. */
    public record NewToken(UUID id, String value) {
    }

    public sealed interface Outcome {
        /** Normal rotation: return the new token to the client. */
        record Rotated(UUID userId, NewToken token) implements Outcome { }
        /** A second tab used the token moments after rotation: issue an access token, keep the newer refresh token. */
        record ConcurrentUse(UUID userId) implements Outcome { }
        record Rejected(String reason) implements Outcome { }
    }

    /** Starts a new family at sign-in. Must run inside a transaction. */
    public NewToken issue(UUID userId, ClientType clientType, String userAgent) {
        return insert(userId, UuidV7.generate(), clientType, userAgent);
    }

    /** Must run inside a transaction: the token row is locked until it commits. */
    public Outcome rotate(String presented, String userAgent) {
        Optional<Row> found = jdbc.sql("""
                SELECT t.id, t.user_id, t.family_id, t.client_type, t.expires_at, t.revoked_at, t.replaced_by,
                       r.issued_at AS replaced_at
                FROM refresh_token t LEFT JOIN refresh_token r ON r.id = t.replaced_by
                WHERE t.token_hash = :hash
                FOR UPDATE OF t
                """)
                .param("hash", hash(presented))
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        ClientType.valueOf(rs.getString("client_type")),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class),
                        rs.getObject("replaced_by", UUID.class),
                        rs.getObject("replaced_at", OffsetDateTime.class)))
                .optional();
        if (found.isEmpty()) {
            return new Outcome.Rejected("unknown");
        }
        Row row = found.get();
        OffsetDateTime now = now();
        if (row.revokedAt() != null) {
            return new Outcome.Rejected("revoked");
        }
        if (row.replacedBy() != null) {
            if (row.replacedAt() != null && row.replacedAt().plus(reuseGrace).isAfter(now)) {
                return new Outcome.ConcurrentUse(row.userId());
            }
            revokeFamily(row.familyId());
            return new Outcome.Rejected("reused");
        }
        if (!row.expiresAt().isAfter(now)) {
            return new Outcome.Rejected("expired");
        }
        NewToken next = insert(row.userId(), row.familyId(), row.clientType(), userAgent);
        jdbc.sql("UPDATE refresh_token SET replaced_by = :next WHERE id = :id")
                .param("next", next.id()).param("id", row.id()).update();
        return new Outcome.Rotated(row.userId(), next);
    }

    /** Sign-out: revokes the presented token's whole family. Unknown tokens are ignored. */
    public void revokeFamilyOf(String presented) {
        jdbc.sql("""
                UPDATE refresh_token SET revoked_at = now()
                WHERE revoked_at IS NULL
                  AND family_id = (SELECT family_id FROM refresh_token WHERE token_hash = :hash)
                """)
                .param("hash", hash(presented)).update();
    }

    /** Suspension, deletion: ends every session of the user. */
    public void revokeAllForUser(UUID userId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE user_id = :user AND revoked_at IS NULL")
                .param("user", userId).update();
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = now() WHERE family_id = :family AND revoked_at IS NULL")
                .param("family", familyId).update();
    }

    private NewToken insert(UUID userId, UUID familyId, ClientType clientType, String userAgent) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UuidV7.generate();
        jdbc.sql("""
                INSERT INTO refresh_token (id, user_id, family_id, token_hash, client_type, issued_at, expires_at, user_agent)
                VALUES (:id, :user, :family, :hash, :type, now(), now() + make_interval(secs => :ttl), :ua)
                """)
                .param("id", id).param("user", userId).param("family", familyId)
                .param("hash", hash(value)).param("type", clientType.name())
                .param("ttl", (double) ttl.toSeconds())
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 300)))
                .update();
        return new NewToken(id, value);
    }

    private OffsetDateTime now() {
        return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(UUID id, UUID userId, UUID familyId, ClientType clientType, OffsetDateTime expiresAt,
                       OffsetDateTime revokedAt, UUID replacedBy, OffsetDateTime replacedAt) {
    }
}
