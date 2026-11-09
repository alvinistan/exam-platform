package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.billing.Payments.Transaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.OffsetDateTime;
import com.leenex.exam.subscription.access.AccessRules;
import com.leenex.exam.subscription.access.AccessService;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.entitlement.EntitlementRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A student's checkout, its status, and their subscription history (FR-SUB-02 … FR-SUB-06). */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final PaymentService service;
    private final Payments payments;
    private final EntitlementRepository entitlements;
    private final AccessService access;
    private final ExamViewRepository exams;
    private final Clock clock;

    public SubscriptionController(PaymentService service, Payments payments, EntitlementRepository entitlements,
                                  ExamViewRepository exams, AccessService access, Clock clock) {
        this.access = access;
        this.service = service;
        this.payments = payments;
        this.entitlements = entitlements;
        this.exams = exams;
        this.clock = clock;
    }

    public record CustomerInput(
            @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Size(max = 50) String lastName,
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9 ()-]{7,20}", message = "must be a phone number") String phone,
            @NotBlank @Size(max = 100) String address,
            @NotBlank @Size(max = 50) String city,
            @NotBlank @Size(max = 50) String country) {
    }

    /** @param currency LKR or USD; defaults to LKR when the plan has an LKR price, otherwise USD */
    public record CheckoutInput(@NotNull UUID planId,
                                @Pattern(regexp = "LKR|USD", message = "must be LKR or USD") String currency,
                                @Size(max = 40) String code,
                                @NotNull @Valid CustomerInput customer) {
    }

    /** @param code a discount code, optional */
    public record QuoteInput(@NotNull UUID planId,
                             @Pattern(regexp = "LKR|USD", message = "must be LKR or USD") String currency,
                             @Size(max = 40) String code) {
    }

    /**
     * The price the student would pay now (sale price, then the code). Checkout works it out again, so this is only
     * for showing; a code that can't be used is a 400 with the reason on the {@code code} field.
     */
    @PostMapping("/quote")
    public com.leenex.exam.subscription.promo.Pricing.Quote quote(@Valid @RequestBody QuoteInput in) {
        return service.quote(CurrentUser.id(), in.planId(), in.currency() == null ? "LKR" : in.currency(), in.code());
    }

    /**
     * Starts paying for a plan. The same Idempotency-Key returns the same payment, so a double click never charges
     * twice. Nothing is activated here: Premium starts when the provider confirms the payment.
     */
    @PostMapping("/checkout")
    public ResponseEntity<PaymentService.Checkout> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody CheckoutInput in) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Idempotency-Key header (8–100 letters, digits, - or _) is required.");
        }
        CustomerInput c = in.customer();
        PaymentService.Checkout checkout = service.checkout(CurrentUser.id(), in.planId(),
                in.currency() == null ? "LKR" : in.currency(), in.code(), key,
                new PaymentService.Customer(c.firstName().strip(), c.lastName().strip(), c.email().strip(),
                        c.phone().strip(), c.address().strip(), c.city().strip(), c.country().strip()));
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
    }

    public record PaymentView(UUID id, String planName, int durationMonths, long amountMinor, String currency,
                              String provider, String status, String failureReason, OffsetDateTime createdAt,
                              OffsetDateTime premiumStartsAt, OffsetDateTime premiumEndsAt, long discountMinor) {
    }

    /** Polled by the return page: the result comes from the provider's notification, never from the browser. */
    @GetMapping("/checkout/{transactionId}")
    public PaymentView status(@PathVariable UUID transactionId) {
        Transaction tx = payments.transaction(transactionId).filter(t -> t.studentId().equals(CurrentUser.id()))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Payment not found."));
        return view(tx);
    }

    /** Attempts used and left in one exam type on one exam; {@code limit} and {@code left} are null when unlimited. */
    public record ModeUse(String mode, Integer limit, int used, Integer left) {
    }

    public record ExamUse(UUID examId, String title, String slug, List<ModeUse> modes) {
    }

    /**
     * One active plan. {@code exams}: what it covers (for scope ALL, only the exams already used under it).
     */
    public record ActivePlan(UUID id, String name, String source, String scopeType, Plans.Limits limits,
                             OffsetDateTime startsAt, OffsetDateTime endsAt, List<ExamUse> exams) {
    }

    public record Mine(boolean premium, OffsetDateTime premiumEndsAt, List<ActivePlan> plans,
                       List<Payments.PeriodView> periods, List<PaymentView> payments) {
    }

    @GetMapping("/me")
    public Mine mine() {
        UUID me = CurrentUser.id();
        List<ActivePlan> plans = activePlans(me);
        OffsetDateTime ends = plans.stream().map(ActivePlan::endsAt).max(OffsetDateTime::compareTo).orElse(null);
        return new Mine(!plans.isEmpty(), ends, plans, payments.periodViews(me),
                payments.transactions(me, 10).stream().map(this::view).toList());
    }

    private List<ActivePlan> activePlans(UUID studentId) {
        List<EntitlementRepository.Grant> grants = access.grants(studentId);
        Map<EntitlementRepository.UseKey, Integer> usage = entitlements.usage(
                grants.stream().map(EntitlementRepository.Grant::id).toList());
        Set<UUID> examIds = new LinkedHashSet<>();
        grants.forEach(g -> examIds.addAll(g.examIds()));
        usage.keySet().forEach(k -> examIds.add(k.examId()));
        Map<UUID, ExamViewRepository.ExamView> views = exams.find(List.copyOf(examIds)).stream()
                .collect(Collectors.toMap(ExamViewRepository.ExamView::examId, Function.identity()));
        List<ActivePlan> result = new ArrayList<>();
        for (EntitlementRepository.Grant g : grants) {
            List<UUID> shown = "ALL".equals(g.scopeType())
                    ? usage.keySet().stream().filter(k -> k.entitlementId().equals(g.id()))
                            .map(EntitlementRepository.UseKey::examId).distinct().toList()
                    : g.examIds();
            List<ExamUse> examUses = shown.stream().map(examId -> {
                ExamViewRepository.ExamView v = views.get(examId);
                List<ModeUse> modes = AccessRules.MODES.stream().filter(m -> {
                    Integer limit = g.limits().forMode(m);
                    return limit == null || limit > 0;
                }).map(m -> {
                    Integer limit = g.limits().forMode(m);
                    int used = usage.getOrDefault(new EntitlementRepository.UseKey(g.id(), examId, m), 0);
                    return new ModeUse(m, limit, used, limit == null ? null : Math.max(0, limit - used));
                }).toList();
                return new ExamUse(examId, v == null ? "" : v.title(), v == null ? "" : v.slug(), modes);
            }).toList();
            result.add(new ActivePlan(g.id(), g.planName() == null ? "Premium" : g.planName(), g.source(),
                    g.scopeType(), g.limits(), g.startsAt(), g.endsAt(), examUses));
        }
        return result;
    }

    private PaymentView view(Transaction tx) {
        var period = payments.periodFor(tx.id()).orElse(null);
        String reason = "CHARGEDBACK".equals(tx.failureReason()) || "NOT_COMPLETED".equals(tx.failureReason())
                ? tx.failureReason() : null;
        return new PaymentView(tx.id(), tx.planName(), tx.durationMonths(), tx.amountMinor(), tx.currency(),
                tx.provider(), tx.status(), reason, tx.createdAt(), period == null ? null : period.startsAt(),
                period == null ? null : period.endsAt(), tx.discountMinor());
    }
}
