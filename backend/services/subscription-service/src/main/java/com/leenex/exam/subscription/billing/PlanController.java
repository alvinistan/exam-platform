package com.leenex.exam.subscription.billing;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plans on sale (public). Prices come from the administrator; nothing here is hardcoded (SB-7). */
@RestController
public class PlanController {

    private final Plans plans;
    private final PaymentService payments;

    public PlanController(Plans plans, PaymentService payments) {
        this.plans = plans;
        this.payments = payments;
    }

    public record PlanView(UUID id, String name, String description, int durationMonths, long priceMinor, String currency) {
        static PlanView of(Plans.Plan p) {
            return new PlanView(p.id(), p.name(), p.description(), p.durationMonths(), p.priceMinor(), p.currency());
        }
    }

    /**
     * @param testPayments true when payments are the labelled stand-in: no money moves and the page must say so
     */
    public record Catalogue(String provider, boolean testPayments, List<PlanView> plans) {
    }

    @GetMapping("/api/plans")
    public Catalogue plans() {
        return new Catalogue(payments.provider(), "TEST".equals(payments.provider()),
                plans.all(true).stream().map(PlanView::of).toList());
    }
}
