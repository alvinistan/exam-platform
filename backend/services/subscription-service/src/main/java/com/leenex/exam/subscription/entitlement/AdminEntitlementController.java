package com.leenex.exam.subscription.entitlement;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.EntitlementRow;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Premium access granted or revoked by an administrator (FR-SUB-11): complimentary access, testing, or revoking
 * after a manual refund (SB-6). Every change is audited; a change takes effect on the next request.
 */
@RestController
@RequestMapping("/api/admin/billing/entitlements")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEntitlementController {

    private static final Duration MAX_LENGTH = Duration.ofDays(5 * 366);

    private final EntitlementRepository entitlements;
    private final AuditLog audit;
    private final Clock clock;

    public AdminEntitlementController(EntitlementRepository entitlements, AuditLog audit, Clock clock) {
        this.entitlements = entitlements;
        this.audit = audit;
        this.clock = clock;
    }

    public record EntitlementView(UUID id, UUID studentId, String source, String state, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, String grantReason, UUID grantedBy, OffsetDateTime revokedAt,
                                  String revokedReason, OffsetDateTime createdAt) {
        static EntitlementView of(EntitlementRow r, Instant now) {
            return new EntitlementView(r.id(), r.studentId(), r.source(), r.state(now), r.startsAt(), r.endsAt(),
                    r.grantReason(), r.grantedBy(), r.revokedAt(), r.revokedReason(), r.createdAt());
        }
    }

    /** Newest first; for one student when {@code studentId} is given. */
    @GetMapping
    public List<EntitlementView> list(@RequestParam(required = false) UUID studentId) {
        Instant now = clock.instant();
        return entitlements.list(studentId, 200).stream().map(r -> EntitlementView.of(r, now)).toList();
    }

    public record GrantInput(@NotNull UUID studentId, @NotNull OffsetDateTime endsAt,
                             @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<EntitlementView> grant(@Valid @RequestBody GrantInput in) {
        // PostgreSQL keeps microseconds and rounds; truncating first means the stored start is never after "now".
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!in.endsAt().toInstant().isAfter(now)) {
            throw Requests.invalid("endsAt", "must be in the future");
        }
        if (in.endsAt().toInstant().isAfter(now.plus(MAX_LENGTH))) {
            throw Requests.invalid("endsAt", "must be within 5 years");
        }
        UUID id = UuidV7.generate();
        entitlements.insertManual(id, in.studentId(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), in.endsAt(),
                in.reason().strip(), CurrentUser.id());
        audit.record(CurrentUser.id(), "ENTITLEMENT_GRANTED", "ENTITLEMENT", id,
                Map.of("studentId", in.studentId().toString(), "endsAt", in.endsAt().toString(), "reason", in.reason().strip()));
        EntitlementRow row = entitlements.lock(id).orElseThrow();
        return ResponseEntity.created(URI.create("/api/admin/billing/entitlements/" + id)).body(EntitlementView.of(row, now));
    }

    public record RevokeInput(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    @PostMapping("/{entitlementId}/revoke")
    @Transactional
    public ResponseEntity<Void> revoke(@PathVariable UUID entitlementId, @Valid @RequestBody RevokeInput in) {
        EntitlementRow row = entitlements.lock(entitlementId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Entitlement not found."));
        if (row.revokedAt() != null) {
            throw new ApiException(ProblemCode.CONFLICT, "This entitlement is already revoked.");
        }
        entitlements.revoke(entitlementId, in.reason().strip());
        audit.record(CurrentUser.id(), "ENTITLEMENT_REVOKED", "ENTITLEMENT", entitlementId,
                Map.of("studentId", row.studentId().toString(), "reason", in.reason().strip()));
        return ResponseEntity.noContent().build();
    }
}
