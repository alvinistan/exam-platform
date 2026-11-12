package com.leenex.exam.subscription.promo;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.billing.Plans;
import com.leenex.exam.subscription.support.Requests;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a student pays for a plan right now (Phase 11C): the regular price, the sale price while a sale is on, then a
 * discount code. The server always works this out; the browser's figures are only shown, never trusted.
 */
@Component
public class Pricing {

    /**
     * @param listPriceMinor the plan's regular price
     * @param priceMinor     the price before the code: the sale price while the sale is on, else the regular price
     * @param saleEndsAt     when the sale price ends, if one applies
     * @param discountMinor  taken off by the code (never more than the price)
     * @param totalMinor     what the student pays; 0 means the code makes it free
     */
    public record Quote(String currency, long listPriceMinor, long priceMinor, OffsetDateTime saleEndsAt,
                        String code, UUID codeId, long discountMinor, long totalMinor) {
    }

    private final DiscountCodes codes;

    public Pricing(DiscountCodes codes) {
        this.codes = codes;
    }

    /**
     * Works out the price. With {@code forCheckout}, the code is locked first so that two checkouts at once can't both
     * take its last use.
     */
    public Quote quote(UUID studentId, Plans.Plan plan, String currency, String code, Instant now, boolean forCheckout) {
        Long regular = plan.priceMinor(currency);
        if (regular == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This plan can't be paid in " + currency + ".");
        }
        long price = plan.priceNow(currency, now);
        OffsetDateTime saleEndsAt = price < regular ? plan.sale().endsAt() : null;
        String normalized = normalize(code);
        if (normalized == null) {
            return new Quote(currency, regular, price, saleEndsAt, null, null, 0, price);
        }
        DiscountCodes.DiscountCode found = codes.byCode(normalized).orElseThrow(() -> refused("This code isn't valid."));
        if (forCheckout) {
            codes.lock(found.id());
            found = codes.find(found.id()).orElseThrow();
        }
        check(found, studentId, plan, now);
        long discount = discount(found, price, currency);
        return new Quote(currency, regular, price, saleEndsAt, found.code(), found.id(), discount, price - discount);
    }

    private void check(DiscountCodes.DiscountCode c, UUID studentId, Plans.Plan plan, Instant now) {
        if (!c.active() || (c.startsAt() != null && now.isBefore(c.startsAt().toInstant()))) {
            throw refused("This code isn't valid.");
        }
        if (c.endsAt() != null && !now.isBefore(c.endsAt().toInstant())) {
            throw refused("This code has expired.");
        }
        if (!c.allPlans() && !c.planIds().contains(plan.id())) {
            throw refused("This code can't be used for this plan.");
        }
        if (c.maxUses() != null && c.uses() >= c.maxUses()) {
            throw refused("This code has been used up.");
        }
        if (codes.usesBy(c.id(), studentId) >= c.maxUsesPerStudent()) {
            throw refused("You've already used this code.");
        }
    }

    /** A percentage is rounded to the nearest cent; a fixed amount needs a value for the currency paid in. */
    static long discount(DiscountCodes.DiscountCode c, long price, String currency) {
        long off;
        if (c.percentOff() != null) {
            off = Math.round(price * c.percentOff() / 100.0);
        } else {
            Long amount = "USD".equals(currency) ? c.amountOffUsdMinor() : c.amountOffLkrMinor();
            if (amount == null) {
                throw refused("This code can't be used when paying in " + currency + ".");
            }
            off = amount;
        }
        return Math.min(Math.max(off, 0), price);
    }

    static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.strip().toUpperCase(Locale.ROOT);
    }

    private static ApiException refused(String message) {
        return Requests.invalid("code", message);
    }
}
