package com.leenex.exam.subscription.access;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The access rules as plain functions, so they are easy to test and identical everywhere they are applied
 * (ADR-007). No database or clock here.
 *
 * <p>Rules (Phase 11B):
 * <ul>
 *   <li>Each exam sets, per exam type (mode), how many attempts any signed-in student may start for free.</li>
 *   <li>A student's plans (bought, or given by an administrator) each cover one exam, a fixed list of exams, or
 *       everything, and allow per exam type: unlimited attempts, N attempts per exam, or none.</li>
 *   <li>A start uses, in order: an unlimited plan; the limited plan that ends soonest with attempts left; then a free
 *       attempt. Each started attempt is counted once, against one allowance.</li>
 *   <li>When nothing is left, that exam type is locked. Past results stay.</li>
 * </ul>
 */
public final class AccessRules {

    public static final List<String> MODES = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");

    private AccessRules() {
    }

    /**
     * One plan's allowance for one exam and one mode.
     *
     * @param limit null = unlimited; otherwise attempts allowed on this exam in this mode (0 is never passed here:
     *              a plan that doesn't include the mode gives no allowance)
     * @param used  attempts already counted against this plan on this exam in this mode
     */
    public record Allowance(UUID entitlementId, Integer limit, int used, OffsetDateTime endsAt) {

        public boolean unlimited() {
            return limit == null;
        }

        public int left() {
            return limit == null ? Integer.MAX_VALUE : Math.max(0, limit - used);
        }
    }

    /**
     * One exam type for one caller. {@code access}: OPEN (an unlimited plan), PLAN (plan attempts left; see
     * {@code planLeft}), FREE (free attempts left, or an anonymous visitor who would get some) or LOCKED. {@code used}
     * and {@code left} count free attempts and are null for anonymous visitors.
     */
    public record ModeAccess(String mode, String access, int freeAttempts, Integer used, Integer left,
                             Integer planLeft) {
    }

    /**
     * What a caller knows about one exam: whether it's available, whether one of their plans covers it, and each mode.
     */
    public record Status(boolean available, boolean premium, List<ModeAccess> modes) {
    }

    /**
     * @param published    the exam is published
     * @param enabledModes modes switched on for the exam
     * @param freeAttempts free attempts per mode (missing = 0)
     * @param covered      one of the caller's active plans covers this exam
     * @param allowances   per mode, the caller's plan allowances for this exam (modes the plans don't include are absent)
     * @param freeUsed     free attempts already counted per mode, or null for anonymous visitors
     */
    public static Status status(boolean published, List<String> enabledModes, Map<String, Integer> freeAttempts,
                                boolean covered, Map<String, List<Allowance>> allowances,
                                Map<String, Integer> freeUsed) {
        if (!published) {
            return new Status(false, covered, List.of());
        }
        List<ModeAccess> modes = MODES.stream().filter(enabledModes::contains).map(mode -> {
            int free = Math.max(0, freeAttempts.getOrDefault(mode, 0));
            List<Allowance> mine = allowances.getOrDefault(mode, List.of());
            Integer counted = freeUsed == null ? null : freeUsed.getOrDefault(mode, 0);
            Integer freeLeft = counted == null ? null : Math.max(0, free - counted);
            if (mine.stream().anyMatch(Allowance::unlimited)) {
                return new ModeAccess(mode, "OPEN", free, counted, freeLeft, null);
            }
            int planLeft = mine.stream().mapToInt(Allowance::left).sum();
            if (planLeft > 0) {
                return new ModeAccess(mode, "PLAN", free, counted, freeLeft, planLeft);
            }
            boolean freeAvailable = freeLeft == null ? free > 0 : freeLeft > 0;
            return new ModeAccess(mode, freeAvailable ? "FREE" : "LOCKED", free, counted, freeLeft,
                    mine.isEmpty() ? null : 0);
        }).toList();
        return new Status(true, covered, modes);
    }

    /**
     * Whether an attempt may start. {@code attemptsLeft}: null when an unlimited plan is used, otherwise how many
     * attempts of that kind (plan or free) remain after this one. Questions always come from the Main bank.
     *
     * <p>Reasons: ACTIVE_ENTITLEMENT (unlimited plan), PLAN_ATTEMPT, FREE_ATTEMPT; refusals EXAM_UNAVAILABLE,
     * MODE_NOT_AVAILABLE, PLAN_ATTEMPTS_USED, FREE_ATTEMPTS_USED, SUBSCRIPTION_REQUIRED.
     */
    public record Decision(boolean allowed, String bank, String reason, Integer attemptsLeft) {
    }

    /** A decision plus the plan that pays for the attempt (null for free attempts and refusals). */
    public record Choice(Decision decision, UUID entitlementId) {
    }

    /**
     * @param allowances this exam's plan allowances for {@code mode}
     * @param freeUsed   free attempts already counted for this student, exam and mode
     */
    public static Choice decide(boolean published, List<String> enabledModes, Map<String, Integer> freeAttempts,
                                String mode, List<Allowance> allowances, int freeUsed) {
        if (!published) {
            return new Choice(new Decision(false, "MAIN", "EXAM_UNAVAILABLE", null), null);
        }
        if (!enabledModes.contains(mode)) {
            return new Choice(new Decision(false, "MAIN", "MODE_NOT_AVAILABLE", null), null);
        }
        Comparator<Allowance> soonest = Comparator.comparing(Allowance::endsAt).thenComparing(Allowance::entitlementId);
        var unlimited = allowances.stream().filter(Allowance::unlimited).min(soonest);
        if (unlimited.isPresent()) {
            return new Choice(new Decision(true, "MAIN", "ACTIVE_ENTITLEMENT", null), unlimited.get().entitlementId());
        }
        var limited = allowances.stream().filter(a -> a.left() > 0).min(soonest);
        if (limited.isPresent()) {
            int leftAfter = allowances.stream().mapToInt(Allowance::left).sum() - 1;
            return new Choice(new Decision(true, "MAIN", "PLAN_ATTEMPT", leftAfter), limited.get().entitlementId());
        }
        int free = Math.max(0, freeAttempts.getOrDefault(mode, 0));
        if (freeUsed < free) {
            return new Choice(new Decision(true, "MAIN", "FREE_ATTEMPT", free - freeUsed - 1), null);
        }
        String reason = !allowances.isEmpty() ? "PLAN_ATTEMPTS_USED"
                : free == 0 ? "SUBSCRIPTION_REQUIRED" : "FREE_ATTEMPTS_USED";
        return new Choice(new Decision(false, "MAIN", reason, 0), null);
    }

    /** Free attempts per mode from a feed payload's map, keeping only known modes and non-negative numbers. */
    public static Map<String, Integer> cleanFreeAttempts(Map<String, Integer> raw) {
        Map<String, Integer> clean = new LinkedHashMap<>();
        for (String mode : MODES) {
            Integer n = raw.get(mode);
            if (n != null && n >= 0) {
                clean.put(mode, n);
            }
        }
        return clean;
    }
}
