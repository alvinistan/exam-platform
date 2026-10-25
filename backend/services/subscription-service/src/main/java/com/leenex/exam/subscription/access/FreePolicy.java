package com.leenex.exam.subscription.access;

import java.util.List;

/**
 * The free-access rules in force (FA-1, FA-2, FA-4), read from {@code free_access_policy} at request time.
 *
 * @param positionActive FA-1 on: the first {@code freeCount} published exams are free
 * @param scope          GLOBAL (one list for the whole catalogue) or CATEGORY (the first N of each category)
 * @param welcomeActive  FA-2 on: a new student may start {@code welcomeAttempts} attempts on premium exams
 * @param featuresActive FA-4 on: free exams offer only {@code freeModes}; off means every mode
 */
public record FreePolicy(boolean positionActive, int freeCount, String scope, boolean welcomeActive, int welcomeAttempts,
                         boolean featuresActive, List<String> freeModes) {

    public static final List<String> ALL_MODES = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");

    public List<String> modesOnFreeExams() {
        return featuresActive ? freeModes : ALL_MODES;
    }
}
