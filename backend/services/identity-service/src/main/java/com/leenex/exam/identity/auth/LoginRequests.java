package com.leenex.exam.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Pending Google sign-ins: state, PKCE verifier and nonce, kept server-side and usable once. */
@Component
public class LoginRequests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public LoginRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Pending(String state, String codeVerifier, String nonce, String returnTo) {

        /** S256 code challenge sent to Google (RFC 7636). */
        public String codeChallenge() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier));
        }
    }

    public Pending create(String returnTo, Duration timeout) {
        Pending pending = new Pending(random(32), random(48), random(24), safeReturnTo(returnTo));
        jdbc.sql("""
                INSERT INTO oauth_login_request (state_hash, code_verifier, nonce, return_to, expires_at)
                VALUES (:hash, :verifier, :nonce, :returnTo, now() + make_interval(secs => :secs))
                """)
                .param("hash", sha256(pending.state())).param("verifier", pending.codeVerifier())
                .param("nonce", pending.nonce()).param("returnTo", pending.returnTo())
                .param("secs", (double) timeout.toSeconds())
                .update();
        return pending;
    }

    /** Removes and returns the request for this state, if it exists and hasn't expired. */
    public Optional<Pending> consume(String state) {
        return jdbc.sql("""
                DELETE FROM oauth_login_request WHERE state_hash = :hash
                RETURNING code_verifier, nonce, return_to, expires_at > now() AS fresh
                """)
                .param("hash", sha256(state))
                .query((rs, n) -> rs.getBoolean("fresh")
                        ? new Pending(state, rs.getString("code_verifier"), rs.getString("nonce"), rs.getString("return_to"))
                        : null)
                .optional();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM oauth_login_request WHERE expires_at < now()").update();
    }

    /**
     * Only same-site paths are allowed after sign-in, so the flow can't be used to send people to another site
     * (open redirect). Anything else returns to the home page.
     */
    static String safeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || returnTo.length() > 200
                || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")
                || returnTo.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "/";
        }
        return returnTo;
    }

    private static String random(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
