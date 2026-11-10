package com.leenex.exam.subscription.billing;

import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.support.Requests;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** What a plan or campaign covers: one exam, a fixed bundle of 2–100 exams, or every exam (ALL). */
public final class Scopes {

    public static final int MAX_BUNDLE = 100;

    private Scopes() {
    }

    /** The exam list to store for a scope, checked; empty for ALL. Unknown exams are refused. */
    public static List<UUID> examIds(String scopeType, List<UUID> requested, ExamViewRepository exams) {
        List<UUID> examIds = List.copyOf(new LinkedHashSet<>(requested == null ? List.of() : requested));
        switch (scopeType) {
            case "EXAM" -> {
                if (examIds.size() != 1) {
                    throw Requests.invalid("examIds", "choose exactly one exam");
                }
            }
            case "BUNDLE" -> {
                if (examIds.size() < 2 || examIds.size() > MAX_BUNDLE) {
                    throw Requests.invalid("examIds", "choose between 2 and " + MAX_BUNDLE + " exams for a bundle");
                }
            }
            default -> {
                return List.of();
            }
        }
        if (exams.find(examIds).size() != examIds.size()) {
            throw Requests.invalid("examIds", "includes an exam that doesn't exist");
        }
        return examIds;
    }

    /** At least one exam type must be included (null = unlimited counts as included). */
    public static void checkLimits(Plans.Limits limits) {
        if (Integer.valueOf(0).equals(limits.practice()) && Integer.valueOf(0).equals(limits.mock())
                && Integer.valueOf(0).equals(limits.adaptive())) {
            throw Requests.invalid("practiceLimit", "include at least one exam type");
        }
    }
}
