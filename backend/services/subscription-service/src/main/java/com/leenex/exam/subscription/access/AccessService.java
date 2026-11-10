package com.leenex.exam.subscription.access;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.access.AccessRules.Allowance;
import com.leenex.exam.subscription.access.AccessRules.Choice;
import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.access.AccessRules.Status;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import com.leenex.exam.subscription.entitlement.EntitlementRepository;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.EntitlementRow;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.Grant;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.UseKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access decisions (ADR-007): computed on every request from the catalogue read model (free attempts per exam type),
 * the student's active plans with their limits, and the attempts already counted. Nothing is cached between requests.
 */
@Service
public class AccessService {

    private final ExamViewRepository exams;
    private final EntitlementRepository entitlements;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AccessService(ExamViewRepository exams, EntitlementRepository entitlements, JdbcClient jdbc, Clock clock) {
        this.exams = exams;
        this.entitlements = entitlements;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Access status for each requested exam; {@code studentId} is null for anonymous visitors. */
    @Transactional(readOnly = true)
    public Map<UUID, Status> statuses(List<UUID> examIds, UUID studentId) {
        Map<UUID, ExamView> found = exams.find(examIds).stream()
                .collect(Collectors.toMap(ExamView::examId, Function.identity()));
        List<Grant> grants = studentId == null ? List.of() : entitlements.activeGrants(studentId, clock.instant());
        Map<UseKey, Integer> usage = entitlements.usage(grants.stream().map(Grant::id).toList());
        Map<UUID, Map<String, Integer>> freeUsed = studentId == null ? Map.of() : freeUsedByExam(studentId, examIds);
        Map<UUID, Status> result = new LinkedHashMap<>();
        for (UUID id : examIds) {
            ExamView exam = found.get(id);
            boolean covered = grants.stream().anyMatch(g -> g.covers(id));
            if (exam == null) {
                result.put(id, AccessRules.status(false, List.of(), Map.of(), covered, Map.of(), null));
                continue;
            }
            Map<String, List<Allowance>> allowances = new HashMap<>();
            for (String mode : AccessRules.MODES) {
                allowances.put(mode, allowances(grants, usage, id, mode));
            }
            result.put(id, AccessRules.status(exam.published(), exam.modes(), exam.freeAttempts(), covered, allowances,
                    studentId == null ? null : freeUsed.getOrDefault(id, Map.of())));
        }
        return result;
    }

    public record MyAccess(boolean premium, EntitlementSummary entitlement) {
    }

    public record EntitlementSummary(String source, OffsetDateTime endsAt) {
    }

    /** Whether the student has any active plan, and the one that ends last. */
    @Transactional(readOnly = true)
    public MyAccess me(UUID studentId) {
        Optional<EntitlementRow> active = entitlements.activeFor(studentId, clock.instant());
        return new MyAccess(active.isPresent(), active.map(e -> new EntitlementSummary(e.source(), e.endsAt())).orElse(null));
    }

    /**
     * Whether a student may start a mode on an exam (FR-ACC-03). With {@code consume}, the attempt is counted once per
     * {@code attemptId} against the allowance that pays for it (a retried start is not counted twice). Starts by the
     * same student are serialised so attempts can't be overspent by parallel requests.
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
        Optional<ExamView> exam = exams.find(List.of(examId)).stream().findFirst();
        if (exam.isEmpty()) {
            return AccessRules.decide(false, List.of(), Map.of(), mode, List.of(), 0).decision();
        }
        List<Grant> grants = entitlements.activeGrants(studentId, clock.instant());
        if (attemptId != null) {
            Optional<Decision> retried = alreadyCounted(attemptId, studentId, exam.get(), mode, grants);
            if (retried.isPresent()) {
                return retried.get();
            }
        }
        Map<UseKey, Integer> usage = entitlements.usage(grants.stream().map(Grant::id).toList());
        int freeUsed = freeUsedByExam(studentId, List.of(examId)).getOrDefault(examId, Map.of()).getOrDefault(mode, 0);
        Choice choice = AccessRules.decide(exam.get().published(), exam.get().modes(), exam.get().freeAttempts(), mode,
                allowances(grants, usage, examId, mode), freeUsed);
        if (consume && choice.decision().allowed()) {
            if (choice.entitlementId() != null) {
                entitlements.recordUse(attemptId, choice.entitlementId(), studentId, examId, mode);
            } else {
                jdbc.sql("""
                        INSERT INTO free_attempt_use (attempt_id, student_id, exam_id, mode) VALUES (:a, :s, :e, :m)
                        ON CONFLICT DO NOTHING
                        """).param("a", attemptId).param("s", studentId).param("e", examId).param("m", mode).update();
            }
        }
        return choice.decision();
    }

    /** A start that was already counted (a retry) is allowed again, without counting it twice. */
    private Optional<Decision> alreadyCounted(UUID attemptId, UUID studentId, ExamView exam, String mode,
                                              List<Grant> grants) {
        if (!exam.published() || !exam.modes().contains(mode)) {
            return Optional.empty();
        }
        Optional<UUID> planUse = entitlements.usedFor(attemptId, studentId);
        if (planUse.isPresent()) {
            boolean unlimited = grants.stream().filter(g -> g.id().equals(planUse.get())).findFirst()
                    .map(g -> g.limits().forMode(mode) == null).orElse(false);
            return Optional.of(new Decision(true, "MAIN", unlimited ? "ACTIVE_ENTITLEMENT" : "PLAN_ATTEMPT", null));
        }
        boolean free = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM free_attempt_use WHERE attempt_id = :a AND student_id = :s)")
                .param("a", attemptId).param("s", studentId).query(Boolean.class).single();
        return free ? Optional.of(new Decision(true, "MAIN", "FREE_ATTEMPT", null)) : Optional.empty();
    }

    /** The plans covering an exam that include a mode, with what each has left. */
    static List<Allowance> allowances(List<Grant> grants, Map<UseKey, Integer> usage, UUID examId, String mode) {
        List<Allowance> list = new ArrayList<>();
        for (Grant g : grants) {
            Integer limit = g.limits().forMode(mode);
            if (!g.covers(examId) || (limit != null && limit == 0)) {
                continue;
            }
            list.add(new Allowance(g.id(), limit, usage.getOrDefault(new UseKey(g.id(), examId, mode), 0), g.endsAt()));
        }
        return list;
    }

    /** Counted free attempts per exam and mode for one student. */
    private Map<UUID, Map<String, Integer>> freeUsedByExam(UUID studentId, List<UUID> examIds) {
        Map<UUID, Map<String, Integer>> used = new HashMap<>();
        if (examIds.isEmpty()) {
            return used;
        }
        jdbc.sql("""
                SELECT exam_id, mode, count(*) AS n FROM free_attempt_use
                WHERE student_id = :s AND exam_id IN (:ids) GROUP BY exam_id, mode
                """).param("s", studentId).param("ids", examIds)
                .query((rs, n) -> used.computeIfAbsent(rs.getObject("exam_id", UUID.class), k -> new HashMap<>())
                        .put(rs.getString("mode"), rs.getInt("n")))
                .list();
        return used;
    }
}
