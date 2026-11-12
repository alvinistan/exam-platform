package com.leenex.exam.subscription.finance;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finance for administrators (Phase 13): payment search, one payment with its refunds, refunds, and the money and
 * subscriber summary. Dates are calendar days in the administrator's time zone ({@code tz}).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminFinanceController {

    private static final Set<Integer> SIZES = Set.of(10, 25, 50, 100);
    private static final Set<String> STATUSES = Set.of("PAID", "FREE", "PENDING", "FAILED", "CANCELLED", "REFUNDED");
    private static final int MAX_DAYS = 400;

    private final FinanceRepository finance;
    private final Refunds refunds;
    private final RefundService refundService;
    private final Clock clock;

    public AdminFinanceController(FinanceRepository finance, Refunds refunds, RefundService refundService, Clock clock) {
        this.finance = finance;
        this.refunds = refunds;
        this.refundService = refundService;
        this.clock = clock;
    }

    public record PageMeta(int page, int size, long totalElements, int totalPages) {
    }

    public record PaymentPage(List<FinanceRepository.PaymentRow> items, PageMeta page) {
    }

    /**
     * Payments, newest first.
     *
     * @param from   first day (inclusive), in {@code tz}
     * @param to     last day (inclusive), in {@code tz}
     * @param status PAID, FREE, PENDING, FAILED, CANCELLED or REFUNDED (any refund went through)
     * @param q      a payment id or PayHere payment id
     */
    @GetMapping("/api/admin/billing/payments")
    public PaymentPage payments(@RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                @RequestParam(defaultValue = "UTC") String tz,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) UUID planId,
                                @RequestParam(required = false) UUID studentId,
                                @RequestParam(required = false) String currency,
                                @RequestParam(required = false) String code,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || page > 10_000 || !SIZES.contains(size)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "page must be 0 or more; size one of 10, 25, 50, 100.");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw Requests.invalid("status", "must be one of " + String.join(", ", STATUSES));
        }
        if (currency != null && !currency.matches("LKR|USD")) {
            throw Requests.invalid("currency", "must be LKR or USD");
        }
        ZoneId zone = zone(tz);
        if (from != null && to != null && to.isBefore(from)) {
            throw Requests.invalid("to", "must be on or after the start date");
        }
        FinanceRepository.Filter filter = new FinanceRepository.Filter(start(from, zone), end(to, zone), status, planId,
                studentId, currency, blankToNull(code, 40, true), blankToNull(q, 100, false));
        long total = finance.count(filter);
        return new PaymentPage(finance.search(filter, page * size, size),
                new PageMeta(page, size, total, (int) ((total + size - 1) / size)));
    }

    /**
     * @param refundableMinor what can still be refunded (paid − refunds pending or done)
     * @param apiRefunds      whether a refund of this payment can go through PayHere's API
     */
    public record PaymentDetail(FinanceRepository.PaymentRow payment, List<Refunds.Refund> refunds,
                                long refundableMinor, boolean apiRefunds) {
    }

    @GetMapping("/api/admin/billing/payments/{transactionId}")
    public PaymentDetail payment(@PathVariable UUID transactionId) {
        FinanceRepository.PaymentRow p = finance.payment(transactionId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Payment not found."));
        boolean refundable = "SUCCEEDED".equals(p.status()) && !"FREE".equals(p.provider());
        long left = refundable ? p.amountMinor() - p.refundedMinor() - p.refundPendingMinor() : 0;
        return new PaymentDetail(p, refunds.forTransaction(transactionId), Math.max(0, left),
                refundService.apiAvailableFor(p.provider()));
    }

    /**
     * @param amountMinor null = everything not refunded yet
     * @param stopAccess  whether the plan this payment bought stops now
     * @param method      PAYHERE_API or MANUAL; null = the API when it's set up for this payment
     * @param reference   for a manual refund: PayHere's refund number, or where the money went
     */
    public record RefundInput(
            @Min(1) @Max(100_000_000_00L) Long amountMinor,
            @NotBlank @Size(min = 3, max = 500) String reason,
            @NotNull Boolean stopAccess,
            @Pattern(regexp = "PAYHERE_API|MANUAL", message = "must be PAYHERE_API or MANUAL") String method,
            @Size(max = 100) String reference) {
    }

    @PostMapping("/api/admin/billing/payments/{transactionId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public Refunds.Refund refund(@PathVariable UUID transactionId, @Valid @RequestBody RefundInput in) {
        return refundService.refund(CurrentUser.id(), transactionId, new RefundService.Request(in.amountMinor(),
                in.reason(), in.stopAccess(), in.method(), in.reference()));
    }

    /** @param outcome SUCCEEDED (the money went back) or FAILED (it didn't) */
    public record ResolveInput(
            @NotNull @Pattern(regexp = "SUCCEEDED|FAILED", message = "must be SUCCEEDED or FAILED") String outcome,
            @Size(max = 100) String reference,
            @Size(max = 500) String note) {
    }

    /** Settles a refund that is still pending, after checking PayHere. */
    @PostMapping("/api/admin/billing/refunds/{refundId}/resolve")
    public Refunds.Refund resolve(@PathVariable UUID refundId, @Valid @RequestBody ResolveInput in) {
        return refundService.resolve(CurrentUser.id(), refundId, "SUCCEEDED".equals(in.outcome()), in.reference(),
                in.note());
    }

    /**
     * @param money        per currency; net = paid − refunded in the range
     * @param endingSoon   students whose last bought plan ends within 30 days, soonest first (at most 50)
     */
    public record Summary(LocalDate from, LocalDate to, String tz, List<FinanceRepository.Money> money,
                          FinanceRepository.Counts payments, List<FinanceRepository.PlanTotal> byPlan,
                          List<FinanceRepository.Day> days, FinanceRepository.Subscribers subscribers,
                          List<FinanceRepository.Ending> endingSoon, boolean apiRefunds) {
    }

    /** Money and subscribers between two days (default: the last 30 days, today included). */
    @GetMapping("/api/admin/billing/finance/summary")
    public Summary summary(@RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to,
                           @RequestParam(defaultValue = "UTC") String tz) {
        ZoneId zone = zone(tz);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate last = to == null ? today : to;
        LocalDate first = from == null ? last.minusDays(29) : from;
        if (last.isBefore(first)) {
            throw Requests.invalid("to", "must be on or after the start date");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw Requests.invalid("from", "choose at most " + MAX_DAYS + " days");
        }
        OffsetDateTime start = start(first, zone);
        OffsetDateTime end = end(last, zone);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new Summary(first, last, zone.getId(), finance.money(start, end), finance.counts(start, end),
                finance.byPlan(start, end), finance.days(start, end, zone.getId()),
                finance.subscribers(now, start, end), finance.endingSoon(now, 30, 50),
                refundService.apiAvailableFor("PAYHERE"));
    }

    private static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw Requests.invalid("tz", "isn't a known time zone");
        }
    }

    private static OffsetDateTime start(LocalDate day, ZoneId zone) {
        return day == null ? null : day.atStartOfDay(zone).toOffsetDateTime();
    }

    private static OffsetDateTime end(LocalDate day, ZoneId zone) {
        return day == null ? null : day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    private static String blankToNull(String text, int max, boolean upper) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > max) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Search text is too long.");
        }
        String t = text.strip();
        return upper ? t.toUpperCase(java.util.Locale.ROOT) : t;
    }
}
