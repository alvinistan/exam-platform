package com.leenex.exam.subscription.promo;

import com.leenex.exam.subscription.billing.Plans;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Free access open to every student right now, for the banner on the site. Public: it shows no student data. */
@RestController
public class OfferController {

    private final Campaigns campaigns;
    private final ExamViewRepository exams;
    private final Clock clock;

    public OfferController(Campaigns campaigns, ExamViewRepository exams, Clock clock) {
        this.campaigns = campaigns;
        this.exams = exams;
        this.clock = clock;
    }

    public record ExamRef(UUID examId, String title, String slug) {
    }

    /** @param exams the published exams included (empty for scope ALL, which includes every exam) */
    public record Offer(UUID id, String name, String scopeType, List<ExamRef> exams, Plans.Limits limits,
                        OffsetDateTime endsAt) {
    }

    @GetMapping("/api/plans/offers")
    public List<Offer> offers() {
        List<Campaigns.Campaign> open = campaigns.openToAll(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        Map<UUID, ExamView> published = exams.find(open.stream().flatMap(c -> c.examIds().stream()).distinct().toList())
                .stream().filter(ExamView::published)
                .collect(Collectors.toMap(ExamView::examId, Function.identity()));
        return open.stream()
                .map(c -> new Offer(c.id(), c.name(), c.scopeType(),
                        c.examIds().stream().map(published::get).filter(java.util.Objects::nonNull)
                                .map(e -> new ExamRef(e.examId(), e.title(), e.slug())).toList(),
                        c.limits(), c.endsAt()))
                .filter(o -> "ALL".equals(o.scopeType()) || !o.exams().isEmpty())
                .toList();
    }
}
