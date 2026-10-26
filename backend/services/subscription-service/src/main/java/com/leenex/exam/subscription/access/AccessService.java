package com.leenex.exam.subscription.access;

import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.access.AccessRules.Status;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import com.leenex.exam.subscription.entitlement.EntitlementRepository;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.EntitlementRow;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access decisions (ADR-007): computed on every request from the policies, the catalogue read model, the student's
 * entitlements, FA-5 claims and welcome-allowance usage. Nothing is cached between requests.
 */
@Service
public class AccessService {

    static final String WELCOME_RULE = "FA2_WELCOME_ALLOWANCE";

    private final PolicyRepository policies;
    private final ExamViewRepository exams;
    private final EntitlementRepository entitlements;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AccessService(PolicyRepository policies, ExamViewRepository exams, EntitlementRepository entitlements,
                         JdbcClient jdbc, Clock clock) {
        this.policies = policies;
        this.exams = exams;
        this.entitlements = entitlements;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Access status for each requested exam; {@code studentId} is null for anonymous visitors. */
    @Transactional(readOnly = true)
    public Map<UUID, Status> statuses(List<UUID> examIds, UUID studentId) {
        FreePolicy policy = policies.current();
        Map<UUID, String> byPosition = AccessRules.freeByPosition(policy, exams.publishedInOrder());
        Set<UUID> published = exams.find(examIds).stream().filter(e -> "PUBLISHED".equals(e.status()))
                .map(ExamView::examId).collect(Collectors.toSet());
        boolean entitled = studentId != null && entitlements.activeFor(studentId, clock.instant()).isPresent();
        Map<UUID, String> claims = studentId == null ? Map.of() : claims(studentId);
        Integer welcomeLeft = studentId == null ? null : welcomeLeft(policy, studentId);
        Map<UUID, Status> result = new LinkedHashMap<>();
        for (UUID id : examIds) {
            result.put(id, AccessRules.status(published.contains(id), byPosition.get(id), claims.get(id), entitled,
                    welcomeLeft, policy));
        }
        return result;
    }

    public record MyAccess(boolean premium, EntitlementSummary entitlement, Integer welcomeAttemptsLeft,
                           int freeExamCount) {
    }

    public record EntitlementSummary(String source, java.time.OffsetDateTime endsAt) {
    }

    @Transactional(readOnly = true)
    public MyAccess me(UUID studentId) {
        FreePolicy policy = policies.current();
        Optional<EntitlementRow> active = entitlements.activeFor(studentId, clock.instant());
        return new MyAccess(active.isPresent(),
                active.map(e -> new EntitlementSummary(e.source(), e.endsAt())).orElse(null),
                welcomeLeft(policy, studentId),
                AccessRules.freeByPosition(policy, exams.publishedInOrder()).size());
    }

    /**
     * Whether a student may start a mode on an exam (FR-ACC-03). With {@code consume}, an allowed start is recorded:
     * a free exam becomes the student's to keep (FA-5) and a welcome attempt is counted once per attempt (FA-5,
     * idempotent on {@code attemptId}). Starts by the same student are serialised so the allowance can't be overspent.
     */
    @Transactional
    public Decision decide(UUID studentId, UUID examId, String mode, boolean consume, UUID attemptId) {
        if (consume && attemptId == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "attemptId is required when consume is true.");
        }
        if (consume) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", studentId.toString())
                    .query((rs, n) -> 1).list();
        }
        FreePolicy policy = policies.current();
        Optional<ExamView> exam = exams.find(List.of(examId)).stream().findFirst();
        boolean published = exam.isPresent() && "PUBLISHED".equals(exam.get().status());
        boolean entitled = entitlements.activeFor(studentId, clock.instant()).isPresent();
        String positionReason = AccessRules.freeByPosition(policy, exams.publishedInOrder()).get(examId);
        String freeReason = positionReason != null ? positionReason
                : claims(studentId).containsKey(examId) ? "FA5_KEPT_FREE" : null;
        // An attempt already counted against the allowance is allowed again (a retried start), whatever is left.
        boolean alreadyCounted = attemptId != null && jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM allowance_usage WHERE rule_code = :rule AND attempt_id = :attempt)
                """).param("rule", WELCOME_RULE).param("attempt", attemptId).query(Boolean.class).single();
        Integer welcomeLeft = alreadyCounted ? Integer.valueOf(1) : welcomeLeft(policy, studentId);
        Decision decision = AccessRules.decide(published, freeReason, entitled, mode, welcomeLeft, policy);

        if (consume && decision.allowed()) {
            if (positionReason != null) {
                jdbc.sql("""
                        INSERT INTO free_exam_claim (student_id, exam_id, reason) VALUES (:s, :e, :r)
                        ON CONFLICT DO NOTHING
                        """).param("s", studentId).param("e", examId).param("r", positionReason).update();
            }
            if ("WELCOME_ALLOWANCE".equals(decision.reason())) {
                jdbc.sql("""
                        INSERT INTO allowance_usage (student_id, rule_code, attempt_id, exam_id)
                        VALUES (:s, :rule, :attempt, :e) ON CONFLICT DO NOTHING
                        """).param("s", studentId).param("rule", WELCOME_RULE).param("attempt", attemptId)
                        .param("e", examId).update();
            }
        }
        return decision;
    }

    private Map<UUID, String> claims(UUID studentId) {
        Map<UUID, String> claims = new HashMap<>();
        jdbc.sql("SELECT exam_id, reason FROM free_exam_claim WHERE student_id = :s").param("s", studentId)
                .query((rs, n) -> claims.put(rs.getObject("exam_id", UUID.class), rs.getString("reason"))).list();
        return claims;
    }

    /** Welcome attempts left (FA-2), or null when the allowance is off. */
    private Integer welcomeLeft(FreePolicy policy, UUID studentId) {
        if (!policy.welcomeActive()) {
            return null;
        }
        int used = jdbc.sql("SELECT count(*) FROM allowance_usage WHERE student_id = :s AND rule_code = :rule")
                .param("s", studentId).param("rule", WELCOME_RULE).query(Integer.class).single();
        return Math.max(0, policy.welcomeAttempts() - used);
    }
}
