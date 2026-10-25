package com.leenex.exam.subscription.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.access.AccessRules.ModeAccess;
import com.leenex.exam.subscription.access.AccessRules.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Free attempts per exam type, on their own (Phase 9). */
class AccessRulesTest {

    static final List<String> ALL = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");
    static final Map<String, Integer> FREE = Map.of("PRACTICE", 3, "MOCK", 1, "USER_FRIENDLY", 3);

    @Test
    void anonymousVisitorsSeeHowManyFreeAttemptsEachTypeHas() {
        Status status = AccessRules.status(true, ALL, Map.of("PRACTICE", 3, "MOCK", 0), false, null);
        assertThat(status.modes()).containsExactly(
                new ModeAccess("PRACTICE", "FREE", 3, null, null),
                new ModeAccess("MOCK", "LOCKED", 0, null, null),
                new ModeAccess("USER_FRIENDLY", "LOCKED", 0, null, null));
    }

    @Test
    void studentsSeeWhatIsLeftAndUsedUpTypesLock() {
        Status status = AccessRules.status(true, ALL, FREE, false, Map.of("PRACTICE", 2, "MOCK", 1));
        assertThat(status.modes()).containsExactly(
                new ModeAccess("PRACTICE", "FREE", 3, 2, 1),
                new ModeAccess("MOCK", "LOCKED", 1, 1, 0),
                new ModeAccess("USER_FRIENDLY", "FREE", 3, 0, 3));
    }

    @Test
    void premiumOpensEverySwitchedOnTypeAndOffTypesAreLeftOut() {
        Status status = AccessRules.status(true, List.of("PRACTICE", "MOCK"), FREE, true, Map.of("MOCK", 5));
        assertThat(status.premium()).isTrue();
        assertThat(status.modes()).extracting(ModeAccess::mode, ModeAccess::access)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PRACTICE", "OPEN"),
                        org.assertj.core.groups.Tuple.tuple("MOCK", "OPEN"));
    }

    @Test
    void unpublishedExamsAreUnavailable() {
        assertThat(AccessRules.status(false, ALL, FREE, true, Map.of()).available()).isFalse();
        assertThat(AccessRules.decide(false, ALL, FREE, true, "PRACTICE", 0, false).reason()).isEqualTo("EXAM_UNAVAILABLE");
    }

    @Test
    void decisionsCountDownAndLockWhenUsedUp() {
        assertThat(AccessRules.decide(true, ALL, FREE, false, "MOCK", 0, false))
                .isEqualTo(new Decision(true, "MAIN", "FREE_ATTEMPT", 0));
        assertThat(AccessRules.decide(true, ALL, FREE, false, "MOCK", 1, false))
                .isEqualTo(new Decision(false, "MAIN", "FREE_ATTEMPTS_USED", 0));
        assertThat(AccessRules.decide(true, ALL, Map.of(), false, "MOCK", 0, false).reason())
                .isEqualTo("SUBSCRIPTION_REQUIRED");
        // A retried start of an attempt that was already counted is allowed again.
        assertThat(AccessRules.decide(true, ALL, FREE, false, "MOCK", 1, true).allowed()).isTrue();
        assertThat(AccessRules.decide(true, ALL, FREE, true, "MOCK", 9, false).reason()).isEqualTo("ACTIVE_ENTITLEMENT");
        assertThat(AccessRules.decide(true, List.of("PRACTICE"), FREE, true, "MOCK", 0, false).reason())
                .isEqualTo("MODE_NOT_AVAILABLE");
    }

    @Test
    void unknownModesAndNegativeNumbersAreDroppedFromTheFeed() {
        java.util.Map<String, Integer> raw = new java.util.HashMap<>();
        raw.put("PRACTICE", 2);
        raw.put("MOCK", -1);
        raw.put("EVERYTHING", 9);
        assertThat(AccessRules.cleanFreeAttempts(raw)).isEqualTo(Map.of("PRACTICE", 2));
    }
}
