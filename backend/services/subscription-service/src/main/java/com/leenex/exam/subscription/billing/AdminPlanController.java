package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plans the administrator sells (FR-SUB-01): length, price and currency, on sale or not. Every change is audited. */
@RestController
@RequestMapping("/api/admin/billing/plans")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPlanController {

    private static final Set<Integer> LENGTHS = Set.of(1, 3, 6, 12);

    private final Plans plans;
    private final AuditLog audit;

    public AdminPlanController(Plans plans, AuditLog audit) {
        this.plans = plans;
        this.audit = audit;
    }

    @GetMapping
    public List<Plans.Plan> list() {
        return plans.all(false);
    }

    /**
     * @param priceMinor price in the currency's smallest unit (cents), e.g. 450000 for LKR 4,500.00
     */
    public record PlanInput(
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,39}", message = "use 2–40 lower-case letters, digits or dashes") String code,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description,
            @NotNull Integer durationMonths,
            @NotNull @Min(100) @Max(100_000_000_00L) Long priceMinor,
            @NotNull @Pattern(regexp = "LKR|USD", message = "must be LKR or USD") String currency,
            boolean active,
            @Min(0) @Max(1000) int displayOrder) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Plans.Plan> create(@Valid @RequestBody PlanInput in) {
        if (in.code() == null) {
            throw Requests.invalid("code", "is required");
        }
        check(in, null);
        UUID id = UuidV7.generate();
        plans.insert(id, in.code(), in.name().strip(), description(in), in.durationMonths(), in.priceMinor(),
                in.currency(), in.active(), in.displayOrder());
        audit.record(CurrentUser.id(), "PLAN_CREATED", "PLAN", id, summary(in));
        return ResponseEntity.created(URI.create("/api/admin/billing/plans/" + id)).body(plans.find(id).orElseThrow());
    }

    /** The code can't change; payments already made keep the name and price they were bought at. */
    @PutMapping("/{planId}")
    @Transactional
    public Plans.Plan update(@PathVariable UUID planId, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @Valid @RequestBody PlanInput in) {
        int version = Requests.ifMatchVersion(ifMatch);
        plans.find(planId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Plan not found."));
        check(in, planId);
        if (!plans.update(planId, version, in.name().strip(), description(in), in.durationMonths(), in.priceMinor(),
                in.currency(), in.active(), in.displayOrder())) {
            throw Requests.staleVersion();
        }
        audit.record(CurrentUser.id(), "PLAN_UPDATED", "PLAN", planId, summary(in));
        return plans.find(planId).orElseThrow();
    }

    private void check(PlanInput in, UUID planId) {
        if (!LENGTHS.contains(in.durationMonths())) {
            throw Requests.invalid("durationMonths", "must be 1, 3, 6 or 12");
        }
        if (planId == null && plans.codeTaken(in.code(), null)) {
            throw Requests.invalid("code", "is already used by another plan");
        }
    }

    private static String description(PlanInput in) {
        return in.description() == null ? "" : in.description().strip();
    }

    private static Map<String, Object> summary(PlanInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", in.name().strip());
        m.put("durationMonths", in.durationMonths());
        m.put("priceMinor", in.priceMinor());
        m.put("currency", in.currency());
        m.put("active", in.active());
        return m;
    }
}
