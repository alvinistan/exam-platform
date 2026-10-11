package com.leenex.exam.identity.user;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A row of {@code app_user} with its roles. */
public record UserRecord(
        UUID id,
        String googleSubject,
        String email,
        String displayName,
        String photoUrl,
        String status,
        String preferencesJson,
        List<String> roles,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt,
        int version) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}
