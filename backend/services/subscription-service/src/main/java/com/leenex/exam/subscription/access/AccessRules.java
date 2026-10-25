package com.leenex.exam.subscription.access;

import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The free-access rules as plain functions, so they are easy to test and identical everywhere they are applied
 * (ADR-007). No database or clock here.
 */
public final class AccessRules {

    private AccessRules() {
    }

    /**
     * FA-1: the exams that are free by catalogue position, with the reason, e.g. {@code FA1_POSITION_2_OF_6} or
     * {@code FA1_CATEGORY_POSITION_1_OF_6}. {@code published} must be in catalogue order.
     */
    public static Map<UUID, String> freeByPosition(FreePolicy policy, List<ExamView> published) {
        Map<UUID, String> free = new LinkedHashMap<>();
        if (!policy.positionActive() || policy.freeCount() <= 0) {
            return free;
        }
        boolean byCategory = "CATEGORY".equals(policy.scope());
        Map<UUID, Integer> rankInCategory = new HashMap<>();
        int globalRank = 0;
        for (ExamView exam : published) {
            int rank = byCategory ? rankInCategory.merge(exam.categoryId(), 1, Integer::sum) : ++globalRank;
            if (rank <= policy.freeCount()) {
                free.put(exam.examId(), (byCategory ? "FA1_CATEGORY_POSITION_" : "FA1_POSITION_") + rank + "_OF_"
                        + policy.freeCount());
            }
        }
        return free;
    }

    /** What one student (or an anonymous visitor) knows about access to one exam. */
    public record Status(String access, String reason, List<String> freeModes, Integer welcomeAttemptsLeft) {
    }

    /**
     * Access status for display (FR-ACC-01). {@code entitled}: an active entitlement (FA-7). {@code claimReason}: the
     * exam stayed free for this student (FA-5), or null. {@code welcomeLeft}: remaining welcome attempts, or null
     * when FA-2 is off or the caller is anonymous.
     */
    public static Status status(boolean published, String positionReason, String claimReason, boolean entitled,
                                Integer welcomeLeft, FreePolicy policy) {
        if (!published) {
            return new Status("LOCKED", "EXAM_UNAVAILABLE", List.of(), null);
        }
        if (positionReason != null) {
            return new Status("FREE", positionReason, policy.modesOnFreeExams(), null);
        }
        if (claimReason != null) {
            return new Status("FREE", "FA5_KEPT_FREE", policy.modesOnFreeExams(), null);
        }
        if (entitled) {
            return new Status("PREMIUM_UNLOCKED", "ACTIVE_ENTITLEMENT", FreePolicy.ALL_MODES, null);
        }
        if (welcomeLeft != null && welcomeLeft > 0) {
            return new Status("LOCKED", "WELCOME_ALLOWANCE", FreePolicy.ALL_MODES, welcomeLeft);
        }
        return new Status("LOCKED", "SUBSCRIPTION_REQUIRED", List.of(), welcomeLeft);
    }

    /** Whether an attempt may start, and from which bank (FA-3: free and welcome access use the Trial bank). */
    public record Decision(boolean allowed, String bank, String reason) {
    }

    /** {@code freeReason}: why the exam is free for this student (position or FA-5), or null. */
    public static Decision decide(boolean published, String freeReason, boolean entitled, String mode,
                                  Integer welcomeLeft, FreePolicy policy) {
        if (!published) {
            return new Decision(false, "TRIAL", "EXAM_UNAVAILABLE");
        }
        if (entitled) {
            return new Decision(true, "MAIN", "ACTIVE_ENTITLEMENT");
        }
        if (freeReason != null) {
            if (policy.modesOnFreeExams().contains(mode)) {
                return new Decision(true, "TRIAL", freeReason);
            }
            // The exam is free but this mode isn't (FA-4); the welcome allowance may still cover it.
            if (welcomeLeft != null && welcomeLeft > 0) {
                return new Decision(true, "TRIAL", "WELCOME_ALLOWANCE");
            }
            return new Decision(false, "TRIAL", "MODE_NOT_FREE");
        }
        if (welcomeLeft != null && welcomeLeft > 0) {
            return new Decision(true, "TRIAL", "WELCOME_ALLOWANCE");
        }
        return new Decision(false, "TRIAL", "SUBSCRIPTION_REQUIRED");
    }
}
