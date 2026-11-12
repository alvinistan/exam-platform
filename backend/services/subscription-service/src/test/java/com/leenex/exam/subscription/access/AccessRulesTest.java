package com.leenex.exam.subscription.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.leenex.exam.subscription.access.AccessRules.Allowance;
import com.leenex.exam.subscription.access.AccessRules.Choice;
import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.access.AccessRules.ModeAccess;
import com.leenex.exam.subscription.access.AccessRules.Status;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRulesTest {

    private static final List<String> ALL = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");
    private static final Map<String, Integer> FREE = Map.of("PRACTICE", 3, "MOCK", 1, "USER_FRIENDLY", 3);
    private static final OffsetDateTime SOON = OffsetDateTime.parse("2026-12-01T00:00:00Z");
    private static final OffsetDateTime LATER = OffsetDateTime.parse("2027-06-01T00:00:00Z");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void anonymousVisitorsSeeWhatIsFree() {
        Status status = AccessRules.status(true, ALL, Map.of("PRACTICE", 3, "MOCK", 0), false, Map.of(), null);
        assertThat(status.modes()).containsExactly(
                new ModeAccess("PRACTICE", "FREE", 3, null, null, null),
                new ModeAccess("MOCK", "LOCKED", 0, null, null, null),
                new ModeAccess("USER_FRIENDLY", "LOCKED", 0, null, null, null));
    }

    @Test
    void studentsWithoutPlansSeeTheirFreeAttemptsLeft() {
        Status status = AccessRules.status(true, ALL, FREE, false, Map.of(), Map.of("PRACTICE", 2, "MOCK", 1));
        assertThat(status.modes()).containsExactly(
                new ModeAccess("PRACTICE", "FREE", 3, 2, 1, null),
                new ModeAccess("MOCK", "LOCKED", 1, 1, 0, null),
                new ModeAccess("USER_FRIENDLY", "FREE", 3, 0, 3, null));
    }

    @Test
    void plansShowUnlimitedOrAttemptsLeftPerMode() {
        Map<String, List<Allowance>> allowances = Map.of(
                "PRACTICE", List.of(new Allowance(A, null, 4, SOON)),
                "MOCK", List.of(new Allowance(A, 8, 3, SOON), new Allowance(B, 2, 0, LATER)),
                "USER_FRIENDLY", List.of(new Allowance(A, 1, 1, SOON)));
        Status status = AccessRules.status(true, ALL, FREE, true, allowances, Map.of("USER_FRIENDLY", 3));
        assertThat(status.premium()).isTrue();
        assertThat(status.modes()).containsExactly(
                new ModeAccess("PRACTICE", "OPEN", 3, 0, 3, null),
                new ModeAccess("MOCK", "PLAN", 1, 0, 1, 7),
                // Plan attempts and free attempts are both used up.
                new ModeAccess("USER_FRIENDLY", "LOCKED", 3, 3, 0, 0));
    }

    @Test
    void onlySwitchedOnModesAppearAndUnpublishedExamsAreUnavailable() {
        Status status = AccessRules.status(true, List.of("PRACTICE", "MOCK"), FREE, false, Map.of(), Map.of());
        assertThat(status.modes()).extracting(ModeAccess::mode).containsExactly("PRACTICE", "MOCK");
        assertThat(AccessRules.status(false, ALL, FREE, true, Map.of(), Map.of()).available()).isFalse();
        assertThat(AccessRules.decide(false, ALL, FREE, "PRACTICE", List.of(), 0).decision().reason())
                .isEqualTo("EXAM_UNAVAILABLE");
        assertThat(AccessRules.decide(true, List.of("PRACTICE"), FREE, "MOCK",
                List.of(new Allowance(A, null, 0, SOON)), 0).decision().reason()).isEqualTo("MODE_NOT_AVAILABLE");
    }

    @Test
    void anUnlimitedPlanIsUsedFirst() {
        Choice choice = AccessRules.decide(true, ALL, FREE, "MOCK",
                List.of(new Allowance(A, 5, 0, SOON), new Allowance(B, null, 40, LATER)), 0);
        assertThat(choice.decision()).isEqualTo(new Decision(true, "MAIN", "ACTIVE_ENTITLEMENT", null));
        assertThat(choice.entitlementId()).isEqualTo(B);
    }

    @Test
    void theLimitedPlanEndingSoonestWithAttemptsLeftIsUsedNext() {
        Choice choice = AccessRules.decide(true, ALL, FREE, "MOCK",
                List.of(new Allowance(B, 10, 2, LATER), new Allowance(A, 3, 1, SOON)), 0);
        assertThat(choice.entitlementId()).isEqualTo(A);
        // 2 left on A + 8 on B, minus this one.
        assertThat(choice.decision()).isEqualTo(new Decision(true, "MAIN", "PLAN_ATTEMPT", 9));

        Choice afterA = AccessRules.decide(true, ALL, FREE, "MOCK",
                List.of(new Allowance(B, 10, 2, LATER), new Allowance(A, 3, 3, SOON)), 0);
        assertThat(afterA.entitlementId()).isEqualTo(B);
    }

    @Test
    void freeAttemptsAreUsedWhenNoPlanHasAttemptsLeft() {
        Choice used = AccessRules.decide(true, ALL, FREE, "PRACTICE", List.of(new Allowance(A, 2, 2, SOON)), 1);
        assertThat(used.decision()).isEqualTo(new Decision(true, "MAIN", "FREE_ATTEMPT", 1));
        assertThat(used.entitlementId()).isNull();
    }

    @Test
    void refusalsSayWhy() {
        assertThat(AccessRules.decide(true, ALL, FREE, "MOCK", List.of(new Allowance(A, 2, 2, SOON)), 1).decision())
                .isEqualTo(new Decision(false, "MAIN", "PLAN_ATTEMPTS_USED", 0));
        assertThat(AccessRules.decide(true, ALL, FREE, "MOCK", List.of(), 1).decision().reason())
                .isEqualTo("FREE_ATTEMPTS_USED");
        assertThat(AccessRules.decide(true, ALL, Map.of(), "MOCK", List.of(), 0).decision().reason())
                .isEqualTo("SUBSCRIPTION_REQUIRED");
    }

    @Test
    void feedPayloadsAreCleaned() {
        assertThat(AccessRules.cleanFreeAttempts(Map.of("PRACTICE", 2, "MOCK", -1, "SPEED", 4)))
                .containsExactly(Map.entry("PRACTICE", 2));
    }
}
