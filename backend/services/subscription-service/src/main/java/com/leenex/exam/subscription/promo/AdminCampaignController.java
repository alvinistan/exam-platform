package com.leenex.exam.subscription.promo;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import com.leenex.exam.subscription.billing.Plans;
import com.leenex.exam.subscription.billing.Scopes;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free access campaigns (Phase 11C): an exam, a bundle or everything, with attempt limits, for every student between
 * two dates, or for chosen students for 1, 3, 6 or 12 months. Every change is audited.
 */
@RestController
@RequestMapping("/api/admin/billing/campaigns")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCampaignController {

    private static final Set<Integer> LENGTHS = Set.of(1, 3, 6, 12);
    private static final int MAX_STUDENTS = 500;

    private final Campaigns campaigns;
    private final ExamViewRepository exams;
    private final AuditLog audit;
    private final Clock clock;

    public AdminCampaignController(Campaigns campaigns, ExamViewRepository exams, AuditLog audit, Clock clock) {
        this.campaigns = campaigns;
        this.exams = exams;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public List<Campaigns.Campaign> list() {
        return campaigns.all();
    }

    /**
     * @param audience       ALL (every student between startsAt and endsAt) or SELECTED (studentIds, for durationMonths
     *                       from today)
     * @param practiceLimit  attempts per exam: null = unlimited, 0 = not included
     */
    public record CampaignInput(
            @NotBlank @Size(min = 2, max = 80) String name,
            @NotNull @Pattern(regexp = "EXAM|BUNDLE|ALL", message = "must be EXAM, BUNDLE or ALL") String scopeType,
            UUID categoryId,
            List<UUID> examIds,
            @Min(0) @Max(10_000) Integer practiceLimit,
            @Min(0) @Max(10_000) Integer mockLimit,
            @Min(0) @Max(10_000) Integer adaptiveLimit,
            @NotNull @Pattern(regexp = "ALL|SELECTED", message = "must be ALL or SELECTED") String audience,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Integer durationMonths,
            @Size(max = MAX_STUDENTS) List<UUID> studentIds) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Campaigns.Campaign> create(@Valid @RequestBody CampaignInput in) {
        Plans.Limits limits = new Plans.Limits(in.practiceLimit(), in.mockLimit(), in.adaptiveLimit());
        Scopes.checkLimits(limits);
        List<UUID> examIds = Scopes.examIds(in.scopeType(), in.examIds(), exams);
        OffsetDateTime now = now();
        UUID id = UuidV7.generate();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", in.name().strip());
        summary.put("scopeType", in.scopeType());
        summary.put("examCount", examIds.size());
        summary.put("audience", in.audience());
        if ("ALL".equals(in.audience())) {
            OffsetDateTime starts = in.startsAt() == null ? now : in.startsAt().truncatedTo(ChronoUnit.MICROS);
            if (in.endsAt() == null || !in.endsAt().isAfter(starts) || !in.endsAt().isAfter(now)) {
                throw Requests.invalid("endsAt", "give an end date in the future, after the start");
            }
            if (in.endsAt().isAfter(starts.plusYears(2))) {
                throw Requests.invalid("endsAt", "a campaign can run for at most 2 years");
            }
            campaigns.insert(id, in.name().strip(), in.scopeType(), in.categoryId(), examIds, limits, "ALL", starts,
                    in.endsAt().truncatedTo(ChronoUnit.MICROS), null, CurrentUser.id());
            summary.put("startsAt", starts.toString());
            summary.put("endsAt", in.endsAt().toString());
        } else {
            if (in.durationMonths() == null || !LENGTHS.contains(in.durationMonths())) {
                throw Requests.invalid("durationMonths", "must be 1, 3, 6 or 12");
            }
            List<UUID> students = students(in.studentIds());
            campaigns.insert(id, in.name().strip(), in.scopeType(), in.categoryId(), examIds, limits, "SELECTED", now,
                    null, in.durationMonths(), CurrentUser.id());
            Campaigns.Campaign created = campaigns.find(id).orElseThrow();
            students.forEach(s -> campaigns.grant(created, s, now, now.plusMonths(in.durationMonths())));
            summary.put("durationMonths", in.durationMonths());
            summary.put("students", students.size());
        }
        audit.record(CurrentUser.id(), "CAMPAIGN_CREATED", "CAMPAIGN", id, summary);
        return ResponseEntity.created(URI.create("/api/admin/billing/campaigns/" + id)).body(campaigns.find(id).orElseThrow());
    }

    public record StudentsInput(@NotNull @Size(min = 1, max = MAX_STUDENTS) List<UUID> studentIds) {
    }

    /** Adds students to a SELECTED campaign; each gets the campaign's length from today. */
    @PostMapping("/{campaignId}/students")
    @Transactional
    public Campaigns.Campaign addStudents(@PathVariable UUID campaignId, @Valid @RequestBody StudentsInput in) {
        Campaigns.Campaign c = open(campaignId);
        if (!"SELECTED".equals(c.audience())) {
            throw new ApiException(ProblemCode.CONFLICT, "This campaign is open to every student already.");
        }
        OffsetDateTime now = now();
        List<UUID> students = students(in.studentIds());
        students.forEach(s -> campaigns.grant(c, s, now, now.plusMonths(c.durationMonths())));
        audit.record(CurrentUser.id(), "CAMPAIGN_STUDENTS_ADDED", "CAMPAIGN", campaignId,
                Map.of("students", students.size()));
        return campaigns.find(campaignId).orElseThrow();
    }

    /** Ends a campaign now; access it gave stops too. */
    @PostMapping("/{campaignId}/end")
    @Transactional
    public Campaigns.Campaign end(@PathVariable UUID campaignId) {
        open(campaignId);
        int revoked = campaigns.end(campaignId);
        audit.record(CurrentUser.id(), "CAMPAIGN_ENDED", "CAMPAIGN", campaignId, Map.of("accessStopped", revoked));
        return campaigns.find(campaignId).orElseThrow();
    }

    private Campaigns.Campaign open(UUID id) {
        Campaigns.Campaign c = campaigns.find(id)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Campaign not found."));
        if (c.endedAt() != null || (c.endsAt() != null && !c.endsAt().toInstant().isAfter(clock.instant()))) {
            throw new ApiException(ProblemCode.CONFLICT, "This campaign has already ended.");
        }
        return c;
    }

    private static List<UUID> students(List<UUID> ids) {
        List<UUID> students = List.copyOf(new LinkedHashSet<>(ids == null ? List.of() : ids));
        if (students.isEmpty()) {
            throw Requests.invalid("studentIds", "choose at least one student");
        }
        return students;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
