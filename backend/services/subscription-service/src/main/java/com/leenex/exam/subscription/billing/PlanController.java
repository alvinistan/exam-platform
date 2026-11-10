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

    public PlanController(Plans plans, PaymentService payments, ExamViewRepository exams) {
        this.plans = plans;
        this.payments = payments;
        this.exams = exams;
    }

    public record ExamRef(UUID examId, String title, String slug) {
    }

    /**
     * @param exams  the published exams the plan covers (empty for scope ALL, which covers every exam)
     * @param limits attempts per exam type, per exam: null = unlimited, 0 = not included
     */
    public record PlanView(UUID id, String name, String description, String scopeType, UUID categoryId,
                           List<ExamRef> exams, int durationMonths, Long priceLkrMinor, Long priceUsdMinor,
                           Plans.Limits limits) {
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
        List<PlanView> views = onSale.stream()
                .map(p -> new PlanView(p.id(), p.name(), p.description(), p.scopeType(), p.categoryId(),
                        p.examIds().stream().map(published::get).filter(java.util.Objects::nonNull)
                                .map(e -> new ExamRef(e.examId(), e.title(), e.slug())).toList(),
                        p.durationMonths(), p.priceLkrMinor(), p.priceUsdMinor(), p.limits()))
                // A plan whose exams are all unpublished has nothing to sell right now.
                .filter(v -> "ALL".equals(v.scopeType()) || !v.exams().isEmpty())
                .toList();
        return new Catalogue(payments.provider(), "TEST".equals(payments.provider()), views);
    }
}
