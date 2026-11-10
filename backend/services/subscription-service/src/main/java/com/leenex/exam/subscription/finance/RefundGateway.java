package com.leenex.exam.subscription.finance;

/** Sends a refund to the payment provider (Phase 13). The only implementation is PayHere's Refund API. */
public interface RefundGateway {

    /**
     * @param ok            the provider accepted and processed the refund
     * @param refundNumber  the provider's refund reference, when ok
     * @param message       the provider's message, for the administrator (never shown to students)
     */
    record Result(boolean ok, String refundNumber, String message) {
    }

    /** Whether refunds can be sent automatically (an API key is configured). */
    boolean available();

    /**
     * Refunds a payment.
     *
     * @param paymentId    the provider's payment id (our transaction's provider_ref)
     * @param amount       "1500.00"; null refunds the whole payment
     * @param description  why, as the administrator wrote it
     */
    Result refund(String paymentId, String amount, String description);
}
