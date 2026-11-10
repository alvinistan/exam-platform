package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.billing.Notification.Outcome;
import com.leenex.exam.subscription.billing.Payments.Transaction;
import com.leenex.exam.subscription.config.SubscriptionProperties;
import com.leenex.exam.subscription.support.AuditLog;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checkout and payment results (ADR-011). Premium starts only from a verified provider result, recorded once per
 * provider message, in the same transaction as the subscription period and the entitlement (SB-1, SB-9).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    /** A checkout nobody finished within this time is marked failed; a late success still activates (money was taken). */
    static final Duration ABANDON_AFTER = Duration.ofHours(24);

    private final Plans plans;
    private final Payments payments;
    private final SubscriptionProperties.Payments settings;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Clock clock;

    public PaymentService(Plans plans, Payments payments, SubscriptionProperties properties, AuditLog audit,
                          JsonMapper json, Clock clock) {
        this.plans = plans;
        this.payments = payments;
        this.settings = properties.payments();
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public String provider() {
        return settings.test() ? "TEST" : "PAYHERE";
    }

    // --- checkout -----------------------------------------------------------------------------------------------------

    /** Buyer details PayHere requires on its form; passed through, not stored. */
    public record Customer(String firstName, String lastName, String email, String phone, String address, String city,
                           String country) {
    }

    /**
     * Where the browser goes next: a form POST to PayHere with signed fields, or (TEST) our own labelled page.
     */
    public record Checkout(UUID transactionId, String provider, String status, URI action, String method,
                           Map<String, String> fields) {
    }

    @Transactional
    public Checkout checkout(UUID studentId, UUID planId, String currency, String idempotencyKey, Customer customer) {
        Transaction tx = payments.byKey(studentId, idempotencyKey).orElse(null);
        if (tx == null) {
            Plans.Plan plan = plans.find(planId).filter(Plans.Plan::active)
                    .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This plan isn't available."));
            Long price = plan.priceMinor(currency);
            if (price == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "This plan can't be paid in " + currency + ".");
            }
            UUID id = UuidV7.generate();
            if (!payments.insertTransaction(id, studentId, plan, provider(), idempotencyKey, price, currency,
                    json.writeValueAsString(Snapshot.of(plan)))) {
                // The same request arrived twice at once; the other one created it.
                tx = payments.byKey(studentId, idempotencyKey).orElseThrow();
                return checkoutFor(tx, customer);
            }
            tx = payments.transaction(id).orElseThrow();
            audit.record(studentId, "CHECKOUT_STARTED", "PAYMENT", id,
                    Map.of("plan", plan.code(), "amountMinor", price, "currency", currency, "provider", provider()));
        } else if (!tx.planId().equals(planId)) {
            throw new ApiException(ProblemCode.CONFLICT, "This request key was already used for a different plan.");
        }
        return checkoutFor(tx, customer);
    }

    /**
     * What was bought, frozen at checkout: activation uses this, never the plan as it is later (an administrator may
     * change the plan while the payment is in flight).
     */
    public record Snapshot(String scopeType, List<UUID> examIds, Plans.Limits limits, int durationMonths) {

        static Snapshot of(Plans.Plan plan) {
            return new Snapshot(plan.scopeType(), plan.examIds(), plan.limits(), plan.durationMonths());
        }
    }

    private Snapshot snapshot(Transaction tx) {
        if (tx.planSnapshot() == null) {
            // Payments made before Phase 11B bought everything, without limits.
            return new Snapshot("ALL", List.of(), Plans.Limits.UNLIMITED, tx.durationMonths());
        }
        return json.readValue(tx.planSnapshot(), Snapshot.class);
    }

    private Checkout checkoutFor(Transaction tx, Customer customer) {
        String base = settings.publicBaseUrl().toString().replaceAll("/+$", "");
        if ("TEST".equals(tx.provider())) {
            return new Checkout(tx.id(), tx.provider(), tx.status(), URI.create(base + "/checkout/test/" + tx.id()), "GET",
                    Map.of());
        }
        SubscriptionProperties.PayHere payhere = settings.payhere();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("merchant_id", payhere.merchantId());
        f.put("return_url", base + "/checkout/return?tx=" + tx.id());
        f.put("cancel_url", base + "/checkout/cancel?tx=" + tx.id());
        f.put("notify_url", base + "/api/payments/webhooks/payhere");
        f.put("first_name", customer.firstName());
        f.put("last_name", customer.lastName());
        f.put("email", customer.email());
        f.put("phone", customer.phone());
        f.put("address", customer.address());
        f.put("city", customer.city());
        f.put("country", customer.country());
        f.put("order_id", tx.id().toString());
        f.put("items", tx.planName());
        f.put("currency", tx.currency());
        f.put("amount", tx.amount());
        f.put("hash", PayHereSigner.checkoutHash(payhere.merchantId(), tx.id().toString(), tx.amount(), tx.currency(),
                payhere.merchantSecret()));
        return new Checkout(tx.id(), tx.provider(), tx.status(), payhere.checkoutUrl(), "POST", f);
    }

    // --- PayHere notifications ----------------------------------------------------------------------------------------

    /**
     * Checks a PayHere notification's signature and turns it into a {@link Notification}; null when it isn't genuine
     * or isn't for this merchant.
     */
    public Notification verifyPayHere(Map<String, String> p) {
        SubscriptionProperties.PayHere payhere = settings.payhere();
        if (settings.test() || payhere.merchantId() == null || !payhere.merchantId().equals(p.get("merchant_id"))) {
            return null;
        }
        String orderId = p.getOrDefault("order_id", "");
        String expected = PayHereSigner.notificationSignature(payhere.merchantId(), orderId, p.getOrDefault("payhere_amount", ""),
                p.getOrDefault("payhere_currency", ""), p.getOrDefault("status_code", ""), payhere.merchantSecret());
        if (!PayHereSigner.matches(expected, p.get("md5sig"))) {
            return null;
        }
        Outcome outcome = Outcome.fromPayHere(p.get("status_code"));
        UUID txId;
        try {
            txId = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (outcome == null) {
            return null;
        }
        String paymentId = p.getOrDefault("payment_id", "");
        return new Notification("PAYHERE", orderId + ":" + p.get("status_code") + ":" + paymentId, txId, outcome,
                paymentId.isBlank() ? null : paymentId, p.get("payhere_amount"), p.get("payhere_currency"),
                p.get("status_message"));
    }

    // --- applying a result --------------------------------------------------------------------------------------------

    /**
     * Applies a verified result once. Returns what happened, which is also stored on the notification record:
     * ACTIVATED, PENDING, CANCELLED, FAILED, CHARGED_BACK, DUPLICATE, UNKNOWN_ORDER, AMOUNT_MISMATCH or IGNORED.
     */
    @Transactional
    public String apply(Notification n, byte[] rawPayload) {
        if (!payments.recordNotification(n.provider(), n.eventId(), n.outcome().name(), sha256(rawPayload))) {
            return "DUPLICATE";
        }
        String outcome = applyOnce(n);
        payments.notificationOutcome(n.provider(), n.eventId(), outcome);
        log.info("Payment notification {} for {}: {}", n.outcome(), n.transactionId(), outcome);
        return outcome;
    }

    private String applyOnce(Notification n) {
        Transaction tx = payments.lockTransaction(n.transactionId()).orElse(null);
        if (tx == null || !tx.provider().equals(n.provider())) {
            return "UNKNOWN_ORDER";
        }
        if (n.amount() == null || n.currency() == null || !tx.currency().equals(n.currency())
                || new BigDecimal(n.amount()).compareTo(BigDecimal.valueOf(tx.amountMinor(), 2)) != 0) {
            audit.record(null, "PAYMENT_AMOUNT_MISMATCH", "PAYMENT", tx.id(),
                    Map.of("expected", tx.amount() + " " + tx.currency(), "received", n.amount() + " " + n.currency()));
            return "AMOUNT_MISMATCH";
        }
        return switch (n.outcome()) {
            case SUCCEEDED -> succeed(tx, n);
            case PENDING -> "PENDING";
            case CANCELLED, FAILED -> {
                if (!"PENDING".equals(tx.status())) {
                    yield "IGNORED";
                }
                payments.setStatus(tx.id(), n.outcome().name(), n.paymentRef(), trim(n.detail()));
                yield n.outcome().name();
            }
            case CHARGED_BACK -> chargeBack(tx, n);
        };
    }

    private String succeed(Transaction tx, Notification n) {
        if ("SUCCEEDED".equals(tx.status()) || "CHARGEDBACK".equals(tx.failureReason())) {
            return "IGNORED";
        }
        // Phase 11B: a plan starts straight away; plans held at the same time add up (the soonest-ending is used first).
        OffsetDateTime start = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        Snapshot bought = snapshot(tx);
        OffsetDateTime end = start.plusMonths(bought.durationMonths());
        UUID periodId = UuidV7.generate();
        payments.setStatus(tx.id(), "SUCCEEDED", n.paymentRef(), null);
        payments.insertPeriod(periodId, tx.studentId(), tx.planId(), tx.id(), start, end);
        payments.insertSubscriptionEntitlement(UuidV7.generate(), tx.studentId(), periodId, start, end, tx.planName(),
                bought.scopeType(), bought.examIds(), bought.limits());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subscriptionId", periodId.toString());
        event.put("studentId", tx.studentId().toString());
        event.put("planId", tx.planId().toString());
        event.put("startsAt", start.toString());
        event.put("endsAt", end.toString());
        payments.outbox("SubscriptionActivated", periodId, json.writeValueAsString(event));
        audit.record(null, "SUBSCRIPTION_ACTIVATED", "SUBSCRIPTION", periodId,
                Map.of("studentId", tx.studentId().toString(), "transactionId", tx.id().toString(),
                        "startsAt", start.toString(), "endsAt", end.toString()));
        return "ACTIVATED";
    }

    private String chargeBack(Transaction tx, Notification n) {
        if (!"SUCCEEDED".equals(tx.status())) {
            return "IGNORED";
        }
        payments.setStatus(tx.id(), "CANCELLED", n.paymentRef(), "CHARGEDBACK");
        payments.periodFor(tx.id()).ifPresent(period -> {
            payments.setPeriodStatus(period.id(), "CANCELLED");
            payments.revokeSubscriptionEntitlement(period.id(), "Payment charged back");
            audit.record(null, "SUBSCRIPTION_CHARGED_BACK", "SUBSCRIPTION", period.id(),
                    Map.of("studentId", tx.studentId().toString(), "transactionId", tx.id().toString()));
        });
        return "CHARGED_BACK";
    }

    /** Whether the student has Premium right now (bought or given). */
    public boolean premiumNow(UUID studentId, OffsetDateTime now) {
        return payments.premiumActiveAt(studentId, now);
    }

    // --- housekeeping -------------------------------------------------------------------------------------------------

    /** Marks finished periods EXPIRED (access already ends at ends_at; this keeps the records honest) and old checkouts failed. */
    @Scheduled(fixedDelayString = "${subscription.payments.sweep-interval:60s}", initialDelayString = "${subscription.payments.sweep-initial-delay:10s}")
    @Transactional
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (Payments.Expired e : payments.expirePeriods(now)) {
            payments.outbox("SubscriptionExpired", e.id(), json.writeValueAsString(
                    Map.of("subscriptionId", e.id().toString(), "studentId", e.studentId().toString())));
        }
        payments.abandonPending(now.minus(ABANDON_AFTER));
    }

    static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload == null ? new byte[0] : payload);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String trim(String s) {
        return s == null ? null : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
