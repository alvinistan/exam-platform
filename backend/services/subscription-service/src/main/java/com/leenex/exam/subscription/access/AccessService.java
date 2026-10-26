package com.leenex.exam.subscription.access;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.access.AccessRules.Status;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import com.leenex.exam.subscription.entitlement.EntitlementRepository;
import com.leenex.exam.subscription.entitlement.EntitlementRepository.EntitlementRow;
import java.time.Clock;
import java.time.OffsetDateTime;
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
 * the student's entitlements and the attempts already counted. Nothing is cached between requests.
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
        boolean premium = studentId != null && entitlements.activeFor(studentId, clock.instant()).isPresent();
        Map<UUID, Map<String, Integer>> used = studentId == null || premium ? Map.of() : usedByExam(studentId, examIds);
        Map<UUID, Status> result = new LinkedHashMap<>();
        for (UUID id : examIds) {
            ExamView exam = found.get(id);
            if (exam == null) {
                result.put(id, AccessRules.status(false, List.of(), Map.of(), premium, null));
                continue;
            }
            result.put(id, AccessRules.status(exam.published(), exam.modes(), exam.freeAttempts(), premium,
                    studentId == null ? null : used.getOrDefault(id, Map.of())));
        }
        return result;
    }

    public record MyAccess(boolean premium, EntitlementSummary entitlement) {
    }

    public record EntitlementSummary(String source, OffsetDateTime endsAt) {
    }

    @Transactional(readOnly = true)
    public MyAccess me(UUID studentId) {
        Optional<EntitlementRow> active = entitlements.activeFor(studentId, clock.instant());
        return new MyAccess(active.isPresent(), active.map(e -> new EntitlementSummary(e.source(), e.endsAt())).orElse(null));
    }

    /**
     * Whether a student may start a mode on an exam (FR-ACC-03). With {@code consume}, an allowed free start is
     * counted once per {@code attemptId} (a retried start is not counted twice). Starts by the same student are
     * serialised so free attempts can't be overspent by parallel requests.
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
            return AccessRules.decide(false, List.of(), Map.of(), false, mode, 0, false);
        }
        boolean premium = entitlements.activeFor(studentId, clock.instant()).isPresent();
        int used = usedByExam(studentId, List.of(examId)).getOrDefault(examId, Map.of()).getOrDefault(mode, 0);
        boolean alreadyCounted = attemptId != null && jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM free_attempt_use WHERE attempt_id = :a AND student_id = :s)")
                .param("a", attemptId).param("s", studentId).query(Boolean.class).single();
        Decision decision = AccessRules.decide(exam.get().published(), exam.get().modes(), exam.get().freeAttempts(),
                premium, mode, used, alreadyCounted);
        if (consume && decision.allowed() && "FREE_ATTEMPT".equals(decision.reason()) && !alreadyCounted) {
            jdbc.sql("""
                    INSERT INTO free_attempt_use (attempt_id, student_id, exam_id, mode) VALUES (:a, :s, :e, :m)
                    ON CONFLICT DO NOTHING
                    """).param("a", attemptId).param("s", studentId).param("e", examId).param("m", mode).update();
        }
        return decision;
    }

    /** Counted free attempts per exam and mode for one student. */
    private Map<UUID, Map<String, Integer>> usedByExam(UUID studentId, List<UUID> examIds) {
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
