package com.leenex.exam.subscription.billing;

import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Plans on sale (public). Prices, scopes and limits come from the administrator; nothing here is hardcoded (SB-7). */
@RestController
public class PlanController {

    private final Plans plans;
    private final PaymentService payments;
    private final ExamViewRepository exams;
    private final java.time.Clock clock;

    public PlanController(Plans plans, PaymentService payments, ExamViewRepository exams, java.time.Clock clock) {
        this.plans = plans;
        this.payments = payments;
        this.exams = exams;
        this.clock = clock;
    }

    public record ExamRef(UUID examId, String title, String slug) {
    }

    /**
     * @param exams  the published exams the plan covers (empty for scope ALL, which covers every exam)
     * @param limits attempts per exam type, per exam: null = unlimited, 0 = not included
     * @param salePriceLkrMinor the sale price while a sale is on now (null otherwise); saleEndsAt says until when
     */
    public record PlanView(UUID id, String name, String description, String scopeType, UUID categoryId,
                           List<ExamRef> exams, int durationMonths, Long priceLkrMinor, Long priceUsdMinor,
                           Plans.Limits limits, Long salePriceLkrMinor, Long salePriceUsdMinor,
                           java.time.OffsetDateTime saleEndsAt) {
    }

    /**
     * @param testPayments true when payments are the labelled stand-in: no money moves and the page must say so
     */
    public record Catalogue(String provider, boolean testPayments, List<PlanView> plans) {
    }

    /** With {@code examId}: only plans that include that exam. With {@code categoryId}: only that category's bundles. */
    @GetMapping("/api/plans")
    public Catalogue plans(@RequestParam(required = false) UUID examId,
                           @RequestParam(required = false) UUID categoryId) {
        List<Plans.Plan> onSale = plans.all(true).stream()
                .filter(p -> examId == null || p.covers(examId))
                .filter(p -> categoryId == null || categoryId.equals(p.categoryId()))
                .toList();
        Map<UUID, ExamView> published = exams.find(onSale.stream().flatMap(p -> p.examIds().stream()).distinct().toList())
                .stream().filter(ExamView::published)
                .collect(Collectors.toMap(ExamView::examId, Function.identity()));
        java.time.Instant now = clock.instant();
        List<PlanView> views = onSale.stream()
                .map(p -> view(p, published, now))
                // A plan whose exams are all unpublished has nothing to sell right now.
                .filter(v -> "ALL".equals(v.scopeType()) || !v.exams().isEmpty())
                .toList();
        return new Catalogue(payments.provider(), "TEST".equals(payments.provider()), views);
    }

    private static PlanView view(Plans.Plan p, Map<UUID, ExamView> published, java.time.Instant now) {
        Long saleLkr = saleIn(p, "LKR", now);
        Long saleUsd = saleIn(p, "USD", now);
        return new PlanView(p.id(), p.name(), p.description(), p.scopeType(), p.categoryId(),
                        p.examIds().stream().map(published::get).filter(java.util.Objects::nonNull)
                                .map(e -> new ExamRef(e.examId(), e.title(), e.slug())).toList(),
                        p.durationMonths(), p.priceLkrMinor(), p.priceUsdMinor(), p.limits(), saleLkr, saleUsd,
                        saleLkr == null && saleUsd == null ? null : p.sale().endsAt());
    }

    private static Long saleIn(Plans.Plan p, String currency, java.time.Instant now) {
        Long regular = p.priceMinor(currency);
        Long price = p.priceNow(currency, now);
        return regular != null && price != null && price < regular ? price : null;
    }
}
