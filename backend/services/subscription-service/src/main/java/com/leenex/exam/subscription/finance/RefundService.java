package com.leenex.exam.subscription.finance;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.billing.Payments;
import com.leenex.exam.subscription.billing.Payments.Transaction;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds (Phase 13). An administrator refunds all or part of a successful payment, with a reason, and chooses whether
 * the plan it bought stops. With a PayHere API key the refund goes through PayHere's Refund API; otherwise (or by
 * choice) the administrator refunds in PayHere's portal and records it here. Every step is audited.
 *
 * <p>A refund through the API is recorded PENDING first and committed, then PayHere is called outside any database
 * transaction, then the result is saved. If the service stops in between, the refund stays PENDING, still counts
 * against the payment (so it can't be refunded twice), and the administrator settles it after checking PayHere.
 */
@Service
public class RefundService {

    public static final String PAYHERE_API = "PAYHERE_API";
    public static final String MANUAL = "MANUAL";

    private final Payments payments;
    private final Refunds refunds;
    private final RefundGateway gateway;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    public RefundService(Payments payments, Refunds refunds, RefundGateway gateway, AuditLog audit,
                         TransactionTemplate tx) {
        this.payments = payments;
        this.refunds = refunds;
        this.gateway = gateway;
        this.audit = audit;
        this.tx = tx;
    }

    /**
     * @param amountMinor null = everything not refunded yet
     * @param method      PAYHERE_API, MANUAL, or null (the API when it's set up and the payment went through PayHere)
     * @param reference   for MANUAL: PayHere's refund number or another note of where the money went
     */
    public record Request(Long amountMinor, String reason, boolean stopAccess, String method, String reference) {
    }

    private record Prepared(UUID refundId, boolean viaApi, String paymentRef, String amount, String reason,
                            UUID transactionId) {
    }

    /** Whether refunds of this payment can go through PayHere's API right now. */
    public boolean apiAvailableFor(String provider) {
        return "PAYHERE".equals(provider) && gateway.available();
    }

    public Refunds.Refund refund(UUID adminId, UUID transactionId, Request in) {
        Prepared p = tx.execute(status -> prepare(adminId, transactionId, in));
        if (p.viaApi()) {
            RefundGateway.Result result = gateway.refund(p.paymentRef(), p.amount(), p.reason());
            tx.executeWithoutResult(status -> settle(adminId, p.refundId(), result.ok(), result.refundNumber(),
                    result.ok() ? null : result.message(), "PAYHERE_REPLY"));
        }
        return refunds.find(p.refundId()).orElseThrow();
    }

    private Prepared prepare(UUID adminId, UUID transactionId, Request in) {
        Transaction t = payments.lockTransaction(transactionId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Payment not found."));
        if ("FREE".equals(t.provider())) {
            throw new ApiException(ProblemCode.CONFLICT, "Nothing was paid for this plan (it was free with a code).");
        }
        if (!"SUCCEEDED".equals(t.status())) {
            throw new ApiException(ProblemCode.CONFLICT, "Only a successful payment can be refunded.");
        }
        long remaining = t.amountMinor() - refunds.committed(t.id());
        if (remaining <= 0) {
            throw new ApiException(ProblemCode.CONFLICT, "This payment has already been refunded in full.");
        }
        long amount = in.amountMinor() == null ? remaining : in.amountMinor();
        if (amount <= 0 || amount > remaining) {
            throw Requests.invalid("amountMinor", "refund between " + money(1, t.currency()) + " and "
                    + money(remaining, t.currency()));
        }
        boolean viaApi = in.method() == null ? apiAvailableFor(t.provider()) : PAYHERE_API.equals(in.method());
        if (viaApi && !apiAvailableFor(t.provider())) {
            throw Requests.invalid("method", "PayHere refunds aren't set up for this payment; record a manual refund");
        }
        if (viaApi && (t.providerRef() == null || t.providerRef().isBlank())) {
            throw Requests.invalid("method", "PayHere's payment id is missing; refund in PayHere and record it manually");
        }
        String reason = in.reason().strip();
        String reference = in.reference() == null || in.reference().isBlank() ? null : in.reference().strip();
        UUID id = UuidV7.generate();
        refunds.insert(id, t.id(), amount, t.currency(), reason, viaApi ? PAYHERE_API : MANUAL,
                viaApi ? "PENDING" : "SUCCEEDED", viaApi ? null : reference, in.stopAccess(), adminId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transactionId", t.id().toString());
        summary.put("studentId", t.studentId().toString());
        summary.put("amountMinor", amount);
        summary.put("currency", t.currency());
        summary.put("method", viaApi ? PAYHERE_API : MANUAL);
        summary.put("stopAccess", in.stopAccess());
        summary.put("reason", reason);
        audit.record(adminId, viaApi ? "REFUND_REQUESTED" : "REFUND_RECORDED", "REFUND", id, summary);
        if (!viaApi) {
            afterSuccess(adminId, refunds.find(id).orElseThrow(), t);
        }
        // PayHere refunds the whole payment when no amount is sent.
        String apiAmount = amount == t.amountMinor() ? null : BigDecimal.valueOf(amount, 2).toPlainString();
        return new Prepared(id, viaApi, t.providerRef(), apiAmount, reason, t.id());
    }

    /**
     * Settles a refund still PENDING (PayHere didn't answer, or the service stopped): the administrator checked PayHere
     * and records whether the money went back.
     */
    public Refunds.Refund resolve(UUID adminId, UUID refundId, boolean succeeded, String reference, String note) {
        tx.executeWithoutResult(status -> {
            Refunds.Refund r = refunds.lock(refundId)
                    .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Refund not found."));
            if (!"PENDING".equals(r.status())) {
                throw new ApiException(ProblemCode.CONFLICT, "This refund is already settled.");
            }
            settle(adminId, refundId, succeeded, reference == null || reference.isBlank() ? null : reference.strip(),
                    succeeded ? null : (note == null || note.isBlank() ? "Marked as not refunded" : note.strip()),
                    "ADMIN");
        });
        return refunds.find(refundId).orElseThrow();
    }

    private void settle(UUID adminId, UUID refundId, boolean ok, String reference, String failure, String by) {
        if (!refunds.settle(refundId, ok ? "SUCCEEDED" : "FAILED", reference, failure)) {
            return;
        }
        Refunds.Refund r = refunds.find(refundId).orElseThrow();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transactionId", r.transactionId().toString());
        summary.put("amountMinor", r.amountMinor());
        summary.put("settledBy", by);
        if (reference != null) {
            summary.put("reference", reference);
        }
        if (failure != null) {
            summary.put("failure", failure);
        }
        audit.record(adminId, ok ? "REFUND_SUCCEEDED" : "REFUND_FAILED", "REFUND", refundId, summary);
        if (ok) {
            afterSuccess(adminId, r, payments.transaction(r.transactionId()).orElseThrow());
        }
    }

    /** When the refund asked for it, the plan the payment bought stops now. */
    private void afterSuccess(UUID adminId, Refunds.Refund r, Transaction t) {
        if (!r.stopAccess()) {
            return;
        }
        payments.periodFor(t.id()).filter(p -> "ACTIVE".equals(p.status())).ifPresent(period -> {
            payments.setPeriodStatus(period.id(), "CANCELLED");
            payments.revokeSubscriptionEntitlement(period.id(), "Refunded");
            audit.record(adminId, "SUBSCRIPTION_REFUNDED", "SUBSCRIPTION", period.id(),
                    Map.of("studentId", t.studentId().toString(), "transactionId", t.id().toString(),
                            "refundId", r.id().toString()));
        });
    }

    static String money(long minor, String currency) {
        return currency + " " + String.format("%,.2f", minor / 100.0);
    }
}
