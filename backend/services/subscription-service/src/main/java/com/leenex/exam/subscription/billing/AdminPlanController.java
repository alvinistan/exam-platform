package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plans the administrator sells (FR-SUB-01, Phase 11B): what they cover (one exam, a fixed bundle, or everything),
 * length, LKR and USD prices, attempt limits per exam type, on sale or hidden. Every change is audited.
 */
@RestController
@RequestMapping("/api/admin/billing/plans")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPlanController {

    private static final Set<Integer> LENGTHS = Set.of(1, 3, 6, 12);
    private static final int MAX_BUNDLE = 100;

    private final Plans plans;
    private final ExamViewRepository exams;
    private final AuditLog audit;

    public AdminPlanController(Plans plans, ExamViewRepository exams, AuditLog audit) {
        this.plans = plans;
        this.exams = exams;
        this.audit = audit;
    }

    @GetMapping
    public List<Plans.Plan> list() {
        return plans.all(false);
    }

    /**
     * @param scopeType      EXAM (exactly one exam), BUNDLE (a fixed list) or ALL (every exam)
     * @param priceLkrMinor  price in cents, e.g. 300000 for LKR 3,000.00; null = not sold in LKR
     * @param priceUsdMinor  price in cents; null = not sold in USD (at least one price is required)
     * @param practiceLimit  attempts per exam for the plan's length: null = unlimited, 0 = not included
     */
    public record PlanInput(
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,39}", message = "use 2–40 lower-case letters, digits or dashes") String code,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description,
            @NotNull @Pattern(regexp = "EXAM|BUNDLE|ALL", message = "must be EXAM, BUNDLE or ALL") String scopeType,
            UUID categoryId,
            List<UUID> examIds,
            @NotNull Integer durationMonths,
            @Min(100) @Max(100_000_000_00L) Long priceLkrMinor,
            @Min(100) @Max(100_000_000_00L) Long priceUsdMinor,
            @Min(0) @Max(10_000) Integer practiceLimit,
            @Min(0) @Max(10_000) Integer mockLimit,
            @Min(0) @Max(10_000) Integer adaptiveLimit,
            boolean active,
            @Min(0) @Max(1000) int displayOrder) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Plans.Plan> create(@Valid @RequestBody PlanInput in) {
        if (in.code() == null) {
            throw Requests.invalid("code", "is required");
        }
        if (plans.codeTaken(in.code())) {
            throw Requests.invalid("code", "is already used by another plan");
        }
        Plans.PlanData data = check(in);
        UUID id = UuidV7.generate();
        plans.insert(id, in.code(), data);
        audit.record(CurrentUser.id(), "PLAN_CREATED", "PLAN", id, summary(data));
        return ResponseEntity.created(URI.create("/api/admin/billing/plans/" + id)).body(plans.find(id).orElseThrow());
    }

    /** The code can't change; payments already made keep what they bought (plan name, price, exams and limits). */
    @PutMapping("/{planId}")
    @Transactional
    public Plans.Plan update(@PathVariable UUID planId, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @Valid @RequestBody PlanInput in) {
        int version = Requests.ifMatchVersion(ifMatch);
        plans.find(planId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Plan not found."));
        Plans.PlanData data = check(in);
        if (!plans.update(planId, version, data)) {
            throw Requests.staleVersion();
        }
        audit.record(CurrentUser.id(), "PLAN_UPDATED", "PLAN", planId, summary(data));
        return plans.find(planId).orElseThrow();
    }

    /** Only a plan nobody has started paying for can be deleted; others are hidden instead, to keep payment records. */
    @DeleteMapping("/{planId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID planId) {
        Plans.Plan plan = plans.find(planId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Plan not found."));
        if (plans.hasPayments(planId)) {
            throw new ApiException(ProblemCode.CONFLICT,
                    "Students have paid for this plan, so it can't be deleted. Hide it instead (turn off “On sale”).");
        }
        plans.delete(planId);
        audit.record(CurrentUser.id(), "PLAN_DELETED", "PLAN", planId, Map.of("name", plan.name()));
        return ResponseEntity.noContent().build();
    }

    private Plans.PlanData check(PlanInput in) {
        if (!LENGTHS.contains(in.durationMonths())) {
            throw Requests.invalid("durationMonths", "must be 1, 3, 6 or 12");
        }
        if (in.priceLkrMinor() == null && in.priceUsdMinor() == null) {
            throw Requests.invalid("priceLkrMinor", "set an LKR price, a USD price, or both");
        }
        if (Integer.valueOf(0).equals(in.practiceLimit()) && Integer.valueOf(0).equals(in.mockLimit())
                && Integer.valueOf(0).equals(in.adaptiveLimit())) {
            throw Requests.invalid("practiceLimit", "include at least one exam type");
        }
        List<UUID> examIds = List.copyOf(new LinkedHashSet<>(in.examIds() == null ? List.of() : in.examIds()));
        switch (in.scopeType()) {
            case "EXAM" -> {
                if (examIds.size() != 1) {
                    throw Requests.invalid("examIds", "choose exactly one exam");
                }
            }
            case "BUNDLE" -> {
                if (examIds.size() < 2 || examIds.size() > MAX_BUNDLE) {
                    throw Requests.invalid("examIds", "choose between 2 and " + MAX_BUNDLE + " exams for a bundle");
                }
            }
            default -> examIds = List.of();
        }
        if (!examIds.isEmpty() && exams.find(examIds).size() != examIds.size()) {
            throw Requests.invalid("examIds", "includes an exam that doesn't exist");
        }
        String description = in.description() == null ? "" : in.description().strip();
        return new Plans.PlanData(in.name().strip(), description, in.scopeType(),
                "ALL".equals(in.scopeType()) ? null : in.categoryId(), examIds, in.durationMonths(),
                in.priceLkrMinor(), in.priceUsdMinor(),
                new Plans.Limits(in.practiceLimit(), in.mockLimit(), in.adaptiveLimit()), in.active(), in.displayOrder());
    }

    private static Map<String, Object> summary(Plans.PlanData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("scopeType", d.scopeType());
        m.put("examCount", d.examIds().size());
        m.put("durationMonths", d.durationMonths());
        m.put("priceLkrMinor", d.priceLkrMinor() == null ? "none" : d.priceLkrMinor());
        m.put("priceUsdMinor", d.priceUsdMinor() == null ? "none" : d.priceUsdMinor());
        m.put("limits", d.limits().practice() + "/" + d.limits().mock() + "/" + d.limits().adaptive());
        m.put("active", d.active());
        return m;
    }
}
