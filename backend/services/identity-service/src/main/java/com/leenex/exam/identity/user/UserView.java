package com.leenex.exam.identity.user;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The User schema of the identity contract. */
public record UserView(
        UUID id,
        String email,
        String displayName,
        String photoUrl,
        List<String> roles,
        String status,
        Map<String, Object> preferences,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt,
        int version) {
}
