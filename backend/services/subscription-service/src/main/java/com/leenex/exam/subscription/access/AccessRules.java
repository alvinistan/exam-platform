package com.leenex.exam.subscription.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The free-access rules as plain functions, so they are easy to test and identical everywhere they are applied
 * (ADR-007). No database or clock here.
 *
 * <p>Rules (Phase 9, replacing the catalogue-position rules):
 * <ul>
 *   <li>Each exam sets, per exam type (mode), how many attempts a student may start without Premium.</li>
 *   <li>Attempts are counted per student, exam and mode; each started attempt counts once.</li>
 *   <li>When they are used up, that exam type is locked until the student has Premium. Past results stay.</li>
 *   <li>A student with an active entitlement may start any switched-on mode without limit.</li>
 * </ul>
 */
public final class AccessRules {

    public static final List<String> MODES = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");

    private AccessRules() {
    }

    /**
     * One exam type for one caller. {@code access}: OPEN (Premium), FREE (free attempts left, or an anonymous visitor
     * who would get some) or LOCKED. {@code used} and {@code left} are null for anonymous visitors and Premium students.
     */
    public record ModeAccess(String mode, String access, int freeAttempts, Integer used, Integer left) {
    }

    /** What a caller knows about one exam: whether it's available, whether they have Premium, and each mode. */
    public record Status(boolean available, boolean premium, List<ModeAccess> modes) {
    }

    /**
     * @param published    the exam is published
     * @param enabledModes modes switched on for the exam
     * @param freeAttempts free attempts per mode (missing = 0)
     * @param premium      the caller has an active entitlement
     * @param used         attempts already counted per mode, or null for anonymous visitors
     */
    public static Status status(boolean published, List<String> enabledModes, Map<String, Integer> freeAttempts,
                                boolean premium, Map<String, Integer> used) {
        if (!published) {
            return new Status(false, premium, List.of());
        }
        List<ModeAccess> modes = MODES.stream().filter(enabledModes::contains).map(mode -> {
            int free = Math.max(0, freeAttempts.getOrDefault(mode, 0));
            if (premium) {
                return new ModeAccess(mode, "OPEN", free, null, null);
            }
            if (used == null) {
                return new ModeAccess(mode, free > 0 ? "FREE" : "LOCKED", free, null, null);
            }
            int counted = used.getOrDefault(mode, 0);
            int left = Math.max(0, free - counted);
            return new ModeAccess(mode, left > 0 ? "FREE" : "LOCKED", free, counted, left);
        }).toList();
        return new Status(true, premium, modes);
    }

    /**
     * Whether an attempt may start. {@code attemptsLeft} is null for Premium starts. Questions always come from the
     * Main bank; the Trial bank is not used for free attempts.
     */
    public record Decision(boolean allowed, String bank, String reason, Integer attemptsLeft) {
    }

    /**
     * @param alreadyCounted this attempt was counted before (a retried start), so it is allowed again
     * @param used           attempts already counted for this student, exam and mode
     */
    public static Decision decide(boolean published, List<String> enabledModes, Map<String, Integer> freeAttempts,
                                  boolean premium, String mode, int used, boolean alreadyCounted) {
        if (!published) {
            return new Decision(false, "MAIN", "EXAM_UNAVAILABLE", null);
        }
        if (!enabledModes.contains(mode)) {
            return new Decision(false, "MAIN", "MODE_NOT_AVAILABLE", null);
        }
        if (premium) {
            return new Decision(true, "MAIN", "ACTIVE_ENTITLEMENT", null);
        }
        int free = Math.max(0, freeAttempts.getOrDefault(mode, 0));
        if (alreadyCounted) {
            return new Decision(true, "MAIN", "FREE_ATTEMPT", Math.max(0, free - used));
        }
        if (used < free) {
            return new Decision(true, "MAIN", "FREE_ATTEMPT", free - used - 1);
        }
        return new Decision(false, "MAIN", free == 0 ? "SUBSCRIPTION_REQUIRED" : "FREE_ATTEMPTS_USED", 0);
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
