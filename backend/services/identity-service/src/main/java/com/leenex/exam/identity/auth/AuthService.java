package com.leenex.exam.identity.auth;

import com.leenex.exam.identity.audit.AuditLog;
import com.leenex.exam.identity.config.IdentityProperties;
import com.leenex.exam.identity.token.AccessTokens;
import com.leenex.exam.identity.token.RefreshTokens;
import com.leenex.exam.identity.token.RefreshTokens.ClientType;
import com.leenex.exam.identity.token.RefreshTokens.NewToken;
import com.leenex.exam.identity.token.RefreshTokens.Outcome;
import com.leenex.exam.identity.user.UserRecord;
import com.leenex.exam.identity.user.UserRepository;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sign-in, token refresh and sign-out (ADR-004). */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final GoogleOidc google;
    private final LoginRequests loginRequests;
    private final UserRepository users;
    private final RefreshTokens refreshTokens;
    private final AccessTokens accessTokens;
    private final AuditLog audit;
    private final IdentityProperties properties;

    public AuthService(GoogleOidc google, LoginRequests loginRequests, UserRepository users, RefreshTokens refreshTokens,
                       AccessTokens accessTokens, AuditLog audit, IdentityProperties properties) {
        this.google = google;
        this.loginRequests = loginRequests;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.audit = audit;
        this.properties = properties;
    }

    public record SignInStart(URI googleUrl, String state) {
    }

    @Transactional
    public SignInStart start(String returnTo) {
        if (!google.configured()) {
            throw new SignInException("not_configured", "GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are not set");
        }
        LoginRequests.Pending pending = loginRequests.create(returnTo, properties.google().loginTimeout());
        return new SignInStart(google.authorizationUrl(pending), pending.state());
    }

    public record SignedIn(UUID userId, NewToken refreshToken, String returnTo) {
    }

    /**
     * Completes the Google redirect. {@code cookieState} is the state stored in this browser at start; it must
     * match the state Google sent back, which proves this browser started the sign-in.
     */
    @Transactional(noRollbackFor = SignInException.class)
    public SignedIn complete(String code, String state, String cookieState, String userAgent) {
        if (state == null || code == null || cookieState == null || !constantTimeEquals(state, cookieState)) {
            throw new SignInException("invalid_state", "Missing or mismatched state");
        }
        LoginRequests.Pending pending = loginRequests.consume(state)
                .orElseThrow(() -> new SignInException("expired", "Sign-in request unknown, used or expired"));

        GoogleOidc.GoogleIdentity identity = google.exchange(code, pending);
        UserRecord user = findOrCreate(identity);
        if (!user.active()) {
            audit.record(user.id(), "SIGN_IN_REFUSED", "USER", user.id(), Map.of("status", user.status()));
            throw new SignInException("suspended", "Account is " + user.status());
        }
        bootstrapAdminIfConfigured(user, identity.email());
        audit.record(user.id(), "SIGNED_IN", "USER", user.id(), Map.of("client", "WEB"));
        return new SignedIn(user.id(), refreshTokens.issue(user.id(), ClientType.WEB, userAgent), pending.returnTo());
    }

    private UserRecord findOrCreate(GoogleOidc.GoogleIdentity identity) {
        return users.findByGoogleSubject(identity.subject())
                .map(existing -> {
                    // Checked first, not caught: a failed statement would abort the whole PostgreSQL transaction.
                    String email = users.emailTaken(identity.email(), existing.id()) ? existing.email() : identity.email();
                    if (!email.equals(identity.email())) {
                        log.warn("Email change for user {} conflicts with another account; not updated", existing.id());
                    }
                    users.recordLogin(existing.id(), email, identity.picture());
                    return existing;
                })
                .orElseGet(() -> {
                    if (users.emailTaken(identity.email(), null)) {
                        throw new SignInException("account_conflict", "Another account already uses this email address");
                    }
                    UUID id = UuidV7.generate();
                    users.insert(id, identity.subject(), identity.email(), identity.name(), identity.picture());
                    users.addRole(id, "STUDENT", null);
                    audit.record(id, "USER_REGISTERED", "USER", id, Map.of());
                    return users.findById(id).orElseThrow();
                });
    }

    /** The first sign-in with the configured bootstrap email becomes administrator; never again (FR-ID-04). */
    private void bootstrapAdminIfConfigured(UserRecord user, String email) {
        String bootstrap = properties.adminBootstrapEmail();
        if (bootstrap == null || bootstrap.isBlank() || !bootstrap.trim().equalsIgnoreCase(email)) {
            return;
        }
        if (users.claimAdminBootstrap(email)) {
            users.addRole(user.id(), "ADMIN", null);
            audit.record(user.id(), "ADMIN_BOOTSTRAPPED", "USER", user.id(), Map.of());
            log.info("First administrator bootstrapped: user {}", user.id());
        }
    }

    public record Refreshed(AccessTokens.Issued access, NewToken newRefreshToken) {
    }

    /**
     * Exchanges a refresh token for a new access token. Runs in one transaction so the rotation and the
     * reuse check are atomic.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Refreshed refresh(String presented, String userAgent) {
        Outcome outcome = refreshTokens.rotate(presented, userAgent);
        UUID userId;
        NewToken next = null;
        switch (outcome) {
            case Outcome.Rotated r -> {
                userId = r.userId();
                next = r.token();
            }
            case Outcome.ConcurrentUse c -> userId = c.userId();
            case Outcome.Rejected r -> {
                if ("reused".equals(r.reason())) {
                    audit.record(null, "REFRESH_TOKEN_REUSE_DETECTED", "REFRESH_TOKEN_FAMILY", null, Map.of());
                }
                throw new ApiException(ProblemCode.UNAUTHENTICATED, "Your session has ended. Please sign in again.");
            }
        }
        UserRecord user = users.findById(userId).orElseThrow(
                () -> new ApiException(ProblemCode.UNAUTHENTICATED, "Your session has ended. Please sign in again."));
        if (!user.active()) {
            refreshTokens.revokeAllForUser(user.id());
            throw new ApiException(ProblemCode.ACCOUNT_SUSPENDED, "This account is suspended.");
        }
        return new Refreshed(accessTokens.issue(user.id(), user.roles(), user.status()), next);
    }

    @Transactional
    public void signOut(String presented) {
        refreshTokens.revokeFamilyOf(presented);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
