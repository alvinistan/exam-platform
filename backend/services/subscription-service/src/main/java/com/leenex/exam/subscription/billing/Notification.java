package com.leenex.exam.subscription.billing;

import java.util.UUID;

/**
 * A verified payment result from a provider (or from the labelled test page), already checked for authenticity.
 *
 * @param eventId    unique per provider message, so a repeated message is recognised (SB-9)
 * @param outcome    what happened to the payment
 * @param paymentRef the provider's own payment id, if any
 * @param amount     amount the provider reports, "4500.00"
 */
public record Notification(String provider, String eventId, UUID transactionId, Outcome outcome, String paymentRef,
                           String amount, String currency, String detail) {

    public enum Outcome {
        SUCCEEDED, PENDING, CANCELLED, FAILED, CHARGED_BACK;

        /** PayHere status_code: 2 success, 0 pending, -1 cancelled, -2 failed, -3 charged back. */
        public static Outcome fromPayHere(String statusCode) {
            return switch (statusCode == null ? "" : statusCode.strip()) {
                case "2" -> SUCCEEDED;
                case "0" -> PENDING;
                case "-1" -> CANCELLED;
                case "-2" -> FAILED;
                case "-3" -> CHARGED_BACK;
                default -> null;
            };
        }
    }
}
