package com.leenex.exam.subscription.access;

import com.leenex.exam.subscription.access.AccessRules.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Access decisions for other services (ADR-007); needs a service token with scope billing.access. */
@RestController
@PreAuthorize("hasAuthority('SCOPE_billing.access')")
public class InternalAccessController {

    private final AccessService access;

    public InternalAccessController(AccessService access) {
        this.access = access;
    }

    public record DecisionInput(@NotNull UUID studentId, @NotNull UUID examId,
                                @NotNull @Pattern(regexp = "PRACTICE|MOCK|USER_FRIENDLY") String mode,
                                Boolean consume, UUID attemptId) {
    }

    @PostMapping("/internal/access-decisions")
    public Decision decide(@Valid @RequestBody DecisionInput in) {
        return access.decide(in.studentId(), in.examId(), in.mode(), Boolean.TRUE.equals(in.consume()), in.attemptId());
    }
}
