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
import java.time.ZoneOffset;
import java.util.List;
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
    private final Clock clock;

    public SubscriptionController(PaymentService service, Payments payments, Clock clock) {
        this.service = service;
        this.payments = payments;
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

    public record CheckoutInput(@NotNull UUID planId, @NotNull @Valid CustomerInput customer) {
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
        PaymentService.Checkout checkout = service.checkout(CurrentUser.id(), in.planId(), key,
                new PaymentService.Customer(c.firstName().strip(), c.lastName().strip(), c.email().strip(),
                        c.phone().strip(), c.address().strip(), c.city().strip(), c.country().strip()));
        return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
    }

    public record PaymentView(UUID id, String planName, int durationMonths, long amountMinor, String currency,
                              String provider, String status, String failureReason, OffsetDateTime createdAt,
                              OffsetDateTime premiumStartsAt, OffsetDateTime premiumEndsAt) {
    }

    /** Polled by the return page: the result comes from the provider's notification, never from the browser. */
    @GetMapping("/checkout/{transactionId}")
    public PaymentView status(@PathVariable UUID transactionId) {
        Transaction tx = payments.transaction(transactionId).filter(t -> t.studentId().equals(CurrentUser.id()))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Payment not found."));
        return view(tx);
    }

    public record Mine(boolean premium, OffsetDateTime premiumEndsAt, List<Payments.PeriodView> periods,
                       List<PaymentView> payments) {
    }

    @GetMapping("/me")
    public Mine mine() {
        UUID me = CurrentUser.id();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime ends = payments.premiumEndsAfter(me, now).orElse(null);
        return new Mine(service.premiumNow(me, now), ends, payments.periodViews(me),
                payments.transactions(me, 10).stream().map(this::view).toList());
    }

    private PaymentView view(Transaction tx) {
        var period = payments.periodFor(tx.id()).orElse(null);
        String reason = "CHARGEDBACK".equals(tx.failureReason()) || "NOT_COMPLETED".equals(tx.failureReason())
                ? tx.failureReason() : null;
        return new PaymentView(tx.id(), tx.planName(), tx.durationMonths(), tx.amountMinor(), tx.currency(),
                tx.provider(), tx.status(), reason, tx.createdAt(), period == null ? null : period.startsAt(),
                period == null ? null : period.endsAt());
    }
}
