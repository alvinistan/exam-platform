package com.leenex.exam.identity.user;

import com.leenex.exam.identity.audit.AuditLog;
import com.leenex.exam.identity.token.RefreshTokens;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Profiles, roles and account status. Every administrative change is audited in the same transaction. */
@Service
public class UserService {

    static final int MAX_PREFERENCES_JSON = 4_000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final UserRepository users;
    private final RefreshTokens refreshTokens;
    private final AuditLog audit;
    private final JsonMapper json;

    public UserService(UserRepository users, RefreshTokens refreshTokens, AuditLog audit, JsonMapper json) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return view(users.findById(id).filter(u -> !"DELETED".equals(u.status())).orElseThrow(UserService::notFound));
    }

    @Transactional
    public UserView updateProfile(UUID id, int expectedVersion, String displayName, Map<String, Object> preferences) {
        String prefs = preferences == null ? null : json.writeValueAsString(preferences);
        if (prefs != null && prefs.length() > MAX_PREFERENCES_JSON) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Preferences are too large.");
        }
        String name = displayName == null ? null : displayName.strip();
        if (name != null && name.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Display name can't be blank.");
        }
        if (!users.updateProfile(id, expectedVersion, name, prefs)) {
            users.findById(id).orElseThrow(UserService::notFound);
            throw new ApiException(ProblemCode.CONFLICT, "Your profile changed elsewhere. Reload and try again.");
        }
        return get(id);
    }

    /** Anonymises the account and ends every session (FR-ID-09). */
    @Transactional
    public void deleteOwnAccount(UUID id) {
        UserRecord user = users.findByIdForUpdate(id).orElseThrow(UserService::notFound);
        if (user.isAdmin() && user.active() && users.countActiveAdmins() <= 1) {
            throw new ApiException(ProblemCode.CONFLICT,
                    "You're the only administrator. Make someone else an administrator first.");
        }
        refreshTokens.revokeAllForUser(id);
        users.anonymise(id);
        audit.record(id, "ACCOUNT_DELETED", "USER", id, Map.of());
    }

    @Transactional(readOnly = true)
    public java.util.List<UserRepository.UserName> names(java.util.Collection<UUID> ids) {
        return users.names(ids);
    }

    @Transactional(readOnly = true)
    public java.util.List<UserRepository.UserContact> contacts(java.util.Collection<UUID> ids) {
        return users.contacts(ids);
    }

    @Transactional(readOnly = true)
    public UserRepository.Page search(String query, String role, String status, int page, int size) {
        return users.search(query, role, status, page, size);
    }

    @Transactional
    public void grantAdmin(UUID actorId, UUID userId) {
        UserRecord user = users.findByIdForUpdate(userId).filter(u -> !"DELETED".equals(u.status()))
                .orElseThrow(UserService::notFound);
        if (user.isAdmin()) {
            return;
        }
        users.addRole(userId, "ADMIN", actorId);
        audit.record(actorId, "ADMIN_ROLE_GRANTED", "USER", userId, Map.of());
    }

    @Transactional
    public void revokeAdmin(UUID actorId, UUID userId) {
        if (actorId.equals(userId)) {
            throw new ApiException(ProblemCode.CONFLICT, "You can't remove your own administrator role.");
        }
        UserRecord user = users.findByIdForUpdate(userId).orElseThrow(UserService::notFound);
        if (!user.isAdmin()) {
            return;
        }
        if (user.active() && users.countActiveAdmins() <= 1) {
            throw new ApiException(ProblemCode.CONFLICT, "The last administrator can't be removed.");
        }
        users.removeRole(userId, "ADMIN");
        audit.record(actorId, "ADMIN_ROLE_REVOKED", "USER", userId, Map.of());
    }

    /** Suspension ends every session at once; existing access tokens expire within 15 minutes (ADR-004). */
    @Transactional
    public void setStatus(UUID actorId, UUID userId, String status, String reason) {
        if (actorId.equals(userId)) {
            throw new ApiException(ProblemCode.CONFLICT, "You can't change your own account status.");
        }
        UserRecord user = users.findByIdForUpdate(userId).filter(u -> !"DELETED".equals(u.status()))
                .orElseThrow(UserService::notFound);
        if (status.equals(user.status())) {
            return;
        }
        if ("SUSPENDED".equals(status) && user.isAdmin() && users.countActiveAdmins() <= 1) {
            throw new ApiException(ProblemCode.CONFLICT, "The last administrator can't be suspended.");
        }
        users.setStatus(userId, status);
        if ("SUSPENDED".equals(status)) {
            refreshTokens.revokeAllForUser(userId);
        }
        audit.record(actorId, "SUSPENDED".equals(status) ? "USER_SUSPENDED" : "USER_REACTIVATED", "USER", userId,
                Map.of("reason", reason));
    }

    UserView view(UserRecord u) {
        Map<String, Object> prefs = u.preferencesJson() == null ? Map.of() : json.readValue(u.preferencesJson(), MAP);
        return new UserView(u.id(), u.email(), u.displayName(), u.photoUrl(), u.roles(), u.status(), prefs,
                u.createdAt(), u.lastLoginAt(), u.version());
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "User not found.");
    }
}
