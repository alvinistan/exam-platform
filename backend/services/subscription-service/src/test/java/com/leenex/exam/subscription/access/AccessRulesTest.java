package com.leenex.exam.subscription.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.leenex.exam.subscription.access.AccessRules.Decision;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The free-access rules on their own (FA-1, FA-2, FA-3, FA-4, FA-5, FA-7). */
class AccessRulesTest {

    static final UUID CAT_A = UUID.randomUUID();
    static final UUID CAT_B = UUID.randomUUID();
    static final FreePolicy DEFAULT = new FreePolicy(true, 6, "GLOBAL", false, 0, true, FreePolicy.ALL_MODES);

    static List<ExamView> exams(int count, UUID category) {
        List<ExamView> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new ExamView(UUID.randomUUID(), category, "Exam " + i, "exam-" + i, i, "PUBLISHED", null));
        }
        return list;
    }

    @Test
    void theFirstSixPublishedExamsAreFree() {
        List<ExamView> published = exams(8, CAT_A);
        Map<java.util.UUID, String> free = AccessRules.freeByPosition(DEFAULT, published);
        assertThat(free).hasSize(6);
        assertThat(free.get(published.get(0).examId())).isEqualTo("FA1_POSITION_1_OF_6");
        assertThat(free.get(published.get(5).examId())).isEqualTo("FA1_POSITION_6_OF_6");
        assertThat(free).doesNotContainKey(published.get(6).examId());
    }

    @Test
    void categoryScopeCountsEachCategorySeparately() {
        List<ExamView> published = new ArrayList<>(exams(3, CAT_A));
        published.addAll(exams(3, CAT_B));
        FreePolicy perCategory = new FreePolicy(true, 2, "CATEGORY", false, 0, true, FreePolicy.ALL_MODES);
        Map<UUID, String> free = AccessRules.freeByPosition(perCategory, published);
        assertThat(free).hasSize(4);
        assertThat(free.get(published.get(3).examId())).isEqualTo("FA1_CATEGORY_POSITION_1_OF_2");
        assertThat(free).doesNotContainKey(published.get(2).examId()).doesNotContainKey(published.get(5).examId());
    }

    @Test
    void aSwitchedOffOrZeroPolicyMakesNothingFree() {
        assertThat(AccessRules.freeByPosition(new FreePolicy(false, 6, "GLOBAL", false, 0, true, FreePolicy.ALL_MODES),
                exams(3, CAT_A))).isEmpty();
        assertThat(AccessRules.freeByPosition(new FreePolicy(true, 0, "GLOBAL", false, 0, true, FreePolicy.ALL_MODES),
                exams(3, CAT_A))).isEmpty();
    }

    @Test
    void statusesForDisplay() {
        assertThat(AccessRules.status(false, null, null, true, null, DEFAULT).reason()).isEqualTo("EXAM_UNAVAILABLE");
        assertThat(AccessRules.status(true, "FA1_POSITION_1_OF_6", null, false, null, DEFAULT).access()).isEqualTo("FREE");
        assertThat(AccessRules.status(true, null, "FA1_POSITION_2_OF_6", false, null, DEFAULT).reason()).isEqualTo("FA5_KEPT_FREE");
        assertThat(AccessRules.status(true, null, null, true, null, DEFAULT).access()).isEqualTo("PREMIUM_UNLOCKED");
        AccessRules.Status welcome = AccessRules.status(true, null, null, false, 2, DEFAULT);
        assertThat(welcome.access()).isEqualTo("LOCKED");
        assertThat(welcome.welcomeAttemptsLeft()).isEqualTo(2);
        assertThat(AccessRules.status(true, null, null, false, 0, DEFAULT).reason()).isEqualTo("SUBSCRIPTION_REQUIRED");
    }

    @Test
    void freeAndWelcomeAccessUseTheTrialBankAndSubscribersTheMainBank() {
        assertThat(AccessRules.decide(true, "FA1_POSITION_1_OF_6", false, "PRACTICE", null, DEFAULT))
                .isEqualTo(new Decision(true, "TRIAL", "FA1_POSITION_1_OF_6"));
        assertThat(AccessRules.decide(true, null, true, "MOCK", null, DEFAULT))
                .isEqualTo(new Decision(true, "MAIN", "ACTIVE_ENTITLEMENT"));
        assertThat(AccessRules.decide(true, null, false, "MOCK", 1, DEFAULT))
                .isEqualTo(new Decision(true, "TRIAL", "WELCOME_ALLOWANCE"));
        assertThat(AccessRules.decide(true, null, false, "MOCK", 0, DEFAULT).reason()).isEqualTo("SUBSCRIPTION_REQUIRED");
        assertThat(AccessRules.decide(false, "FA1_POSITION_1_OF_6", true, "MOCK", 1, DEFAULT).allowed()).isFalse();
    }

    @Test
    void freeExamsOfferOnlyTheFreeModes() {
        FreePolicy practiceOnly = new FreePolicy(true, 6, "GLOBAL", false, 0, true, List.of("PRACTICE"));
        assertThat(AccessRules.decide(true, "FA1_POSITION_1_OF_6", false, "MOCK", null, practiceOnly).reason())
                .isEqualTo("MODE_NOT_FREE");
        assertThat(AccessRules.decide(true, "FA1_POSITION_1_OF_6", false, "PRACTICE", null, practiceOnly).allowed()).isTrue();
        FreePolicy featuresOff = new FreePolicy(true, 6, "GLOBAL", false, 0, false, List.of("PRACTICE"));
        assertThat(featuresOff.modesOnFreeExams()).isEqualTo(FreePolicy.ALL_MODES);
    }
}
