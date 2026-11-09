package com.leenex.exam.subscription.promo;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.billing.Plans;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

/**
 * Discount codes (Phase 11C). A code can't be deleted once used, so payment records keep it; turn it off instead.
 * Every change is audited.
 */
@RestController
@RequestMapping("/api/admin/billing/discount-codes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiscountCodeController {

    private static final int MAX_PLANS = 100;

    private final DiscountCodes codes;
    private final Plans plans;
    private final AuditLog audit;
    private final java.time.Clock clock;

    public AdminDiscountCodeController(DiscountCodes codes, Plans plans, AuditLog audit, java.time.Clock clock) {
        this.codes = codes;
        this.plans = plans;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public List<DiscountCodes.DiscountCode> list() {
        return codes.all();
    }

    /**
     * Either {@code percentOff}, or a fixed amount in LKR and/or USD (a code without an amount for a currency can't be
     * used when paying in it).
     *
     * @param code              case doesn't matter; stored in capitals. Can't change after creation.
     * @param startsAt          defaults to now
     * @param maxUses           in total; null = no limit
     * @param maxUsesPerStudent how many payments one student may use it for (1 by default)
     */
    public record CodeInput(
            @Size(max = 32) String code,
            @Size(max = 200) String description,
            @Min(1) @Max(100) Integer percentOff,
            @Min(1) @Max(100_000_000_00L) Long amountOffLkrMinor,
            @Min(1) @Max(100_000_000_00L) Long amountOffUsdMinor,
            boolean allPlans,
            @Size(max = MAX_PLANS) List<UUID> planIds,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            @Min(1) @Max(1_000_000) Integer maxUses,
            @Min(1) @Max(100) Integer maxUsesPerStudent,
            boolean active) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<DiscountCodes.DiscountCode> create(@Valid @RequestBody CodeInput in) {
        String code = Pricing.normalize(in.code());
        if (code == null || !code.matches("[A-Z0-9][A-Z0-9_-]{2,31}")) {
            throw Requests.invalid("code", "use 3–32 letters, digits, - or _");
        }
        if (codes.codeTaken(code)) {
            throw Requests.invalid("code", "is already used by another code");
        }
        DiscountCodes.CodeData data = check(in);
        UUID id = UuidV7.generate();
        codes.insert(id, code, data);
        Map<String, Object> summary = summary(data);
        summary.put("code", code);
        audit.record(CurrentUser.id(), "DISCOUNT_CODE_CREATED", "DISCOUNT_CODE", id, summary);
        return ResponseEntity.created(URI.create("/api/admin/billing/discount-codes/" + id))
                .body(codes.find(id).orElseThrow());
    }

    /** Everything but the code itself can change; payments already made keep the discount they got. */
    @PutMapping("/{codeId}")
    @Transactional
    public DiscountCodes.DiscountCode update(@PathVariable UUID codeId,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @Valid @RequestBody CodeInput in) {
        int version = Requests.ifMatchVersion(ifMatch);
        codes.find(codeId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Discount code not found."));
        DiscountCodes.CodeData data = check(in);
        if (!codes.update(codeId, version, data)) {
            throw Requests.staleVersion();
        }
        audit.record(CurrentUser.id(), "DISCOUNT_CODE_UPDATED", "DISCOUNT_CODE", codeId, summary(data));
        return codes.find(codeId).orElseThrow();
    }

    private DiscountCodes.CodeData check(CodeInput in) {
        boolean percent = in.percentOff() != null;
        boolean amount = in.amountOffLkrMinor() != null || in.amountOffUsdMinor() != null;
        if (percent == amount) {
            throw Requests.invalid("percentOff", "give either a percentage or an amount off, not both");
        }
        OffsetDateTime starts = in.startsAt() == null
                ? OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC) : in.startsAt();
        if (in.endsAt() == null || !minutes(in.endsAt()).isAfter(minutes(starts))) {
            throw Requests.invalid("endsAt", "give an end date after the start");
        }
        List<UUID> planIds = List.copyOf(new LinkedHashSet<>(in.planIds() == null ? List.of() : in.planIds()));
        if (!in.allPlans()) {
            if (planIds.isEmpty()) {
                throw Requests.invalid("planIds", "choose at least one plan, or allow all plans");
            }
            for (UUID planId : planIds) {
                if (plans.find(planId).isEmpty()) {
                    throw Requests.invalid("planIds", "one of the plans doesn't exist");
                }
            }
        }
        String description = in.description() == null ? "" : in.description().strip();
        return new DiscountCodes.CodeData(description, in.percentOff(), in.amountOffLkrMinor(), in.amountOffUsdMinor(),
                in.allPlans(), in.allPlans() ? List.of() : planIds, minutes(starts), minutes(in.endsAt()),
                in.maxUses(), in.maxUsesPerStudent() == null ? 1 : in.maxUsesPerStudent(), in.active());
    }

    private static OffsetDateTime minutes(OffsetDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MINUTES);
    }

    private static Map<String, Object> summary(DiscountCodes.CodeData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("discount", d.percentOff() != null ? d.percentOff() + "%"
                : "LKR " + d.amountOffLkrMinor() + " / USD " + d.amountOffUsdMinor() + " (minor units)");
        m.put("plans", d.allPlans() ? "all" : String.valueOf(d.planIds().size()));
        m.put("maxUses", d.maxUses() == null ? "unlimited" : d.maxUses());
        m.put("maxUsesPerStudent", d.maxUsesPerStudent());
        m.put("active", d.active());
        return m;
    }
}
