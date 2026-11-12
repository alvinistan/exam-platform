package com.leenex.exam.subscription.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * PayHere's checkout hash and notification signature (support.payhere.lk, Checkout API). Both are an upper-case MD5 of
 * fields joined with the upper-case MD5 of the merchant secret. MD5 is PayHere's choice, not ours; the secret never
 * leaves the server.
 */
public final class PayHereSigner {

    private PayHereSigner() {
    }

    /** {@code hash} for the checkout form: merchant_id + order_id + amount + currency + MD5(secret). */
    public static String checkoutHash(String merchantId, String orderId, String amount, String currency, String secret) {
        return md5(merchantId + orderId + amount + currency + md5(secret));
    }

    /** Expected {@code md5sig} of a notification: merchant_id + order_id + payhere_amount + currency + status + MD5(secret). */
    public static String notificationSignature(String merchantId, String orderId, String amount, String currency,
                                               String statusCode, String secret) {
        return md5(merchantId + orderId + amount + currency + statusCode + md5(secret));
    }

    /** Compares in constant time so the check doesn't leak how much of a forged signature was right. */
    public static boolean matches(String expected, String received) {
        if (received == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                received.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
