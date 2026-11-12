package com.leenex.exam.assessment.attempt;

import static org.assertj.core.api.Assertions.assertThat;

import com.leenex.exam.assessment.attempt.QuestionPicker.Choice;
import com.leenex.exam.assessment.attempt.QuestionPicker.Context;
import com.leenex.exam.assessment.attempt.QuestionPicker.Picked;
import com.leenex.exam.assessment.attempt.QuestionPicker.Size;
import com.leenex.exam.assessment.client.CatalogClient.PoolOption;
import com.leenex.exam.assessment.client.CatalogClient.PoolQuestion;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Choosing a session's questions (SG-1 … SG-6). */
class QuestionPickerTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();

    static PoolQuestion q(UUID topic, int seconds) {
        return q(topic, seconds, "MEDIUM");
    }

    static PoolQuestion q(UUID topic, int seconds, String difficulty) {
        return new PoolQuestion(UUID.randomUUID(), UUID.randomUUID(), topic, "SINGLE", difficulty, seconds, BigDecimal.ONE,
                List.of(new PoolOption("A", true, null), new PoolOption("B", false, null)));
    }

    /** {@code each} questions of every difficulty in the topic. */
    static List<PoolQuestion> mixed(UUID topic, int each) {
        List<PoolQuestion> list = new ArrayList<>();
        for (String d : List.of("SIMPLE", "MEDIUM", "HARD")) {
            for (int i = 0; i < each; i++) {
                list.add(q(topic, 60, d));
            }
        }
        return list;
    }

    static long count(List<PoolQuestion> qs, String difficulty) {
        return qs.stream().filter(x -> x.difficulty().equals(difficulty)).count();
    }

    @Test
    void theStudentsLevelOnATopicDecidesTheDifficulty() {
        List<PoolQuestion> pool = new ArrayList<>(mixed(A, 80));
        pool.addAll(mixed(B, 80));
        Context ctx = new Context(java.util.Map.of(A, 1, B, 3), java.util.Map.of(), Set.of(), Set.of(), false);
        // 100 questions per topic, so the proportions are reliable: about 70/30 and 40/60.
        List<PoolQuestion> chosen = QuestionPicker.pick(pool, ctx, Size.count(200), 200, false, new Random(42)).questions();
        List<PoolQuestion> easyTopic = chosen.stream().filter(x -> x.topicId().equals(A)).toList();
        List<PoolQuestion> hardTopic = chosen.stream().filter(x -> x.topicId().equals(B)).toList();
        assertThat(easyTopic).hasSize(100);
        assertThat(count(easyTopic, "HARD")).isZero();
        assertThat(count(easyTopic, "SIMPLE")).isBetween(55L, 85L);
        assertThat(count(hardTopic, "SIMPLE")).isZero();
        assertThat(count(hardTopic, "HARD")).isBetween(45L, 75L);
    }

    @Test
    void whenADifficultyRunsOutTheNearestOneIsUsed() {
        List<PoolQuestion> pool = new ArrayList<>(List.of(q(A, 60, "SIMPLE"), q(A, 60, "MEDIUM")));
        Context hard = new Context(java.util.Map.of(A, 3), java.util.Map.of(), Set.of(), Set.of(), false);
        Choice first = QuestionPicker.next(pool, List.of(), hard, null, new Random(1)).orElseThrow();
        assertThat(first.question().difficulty()).isEqualTo("MEDIUM");
        assertThat(first.level()).isEqualTo(3);
    }

    @Test
    void questionsAnsweredWrongComeBackFirstButNeverTakeOverTheSession() {
        List<PoolQuestion> pool = mixed(A, 10);
        Set<UUID> retry = pool.stream().limit(12).map(PoolQuestion::questionId).collect(Collectors.toSet());
        Context ctx = new Context(java.util.Map.of(), java.util.Map.of(), retry, retry, true);
        List<PoolQuestion> twelve = QuestionPicker.pick(pool, ctx, Size.count(12), 30, false, new Random(3)).questions();
        long retried = twelve.stream().filter(x -> retry.contains(x.questionId())).count();
        assertThat(retried).isBetween(3L, 4L); // about a quarter
        assertThat(retry).contains(twelve.getFirst().questionId());
        // A mock exam doesn't repeat mistakes on purpose.
        Context mock = new Context(java.util.Map.of(), java.util.Map.of(), retry, retry, false);
        List<PoolQuestion> mockSet = QuestionPicker.pick(pool, mock, Size.count(12), 30, false, new Random(3)).questions();
        assertThat(mockSet.stream().filter(x -> retry.contains(x.questionId()))).isEmpty();
    }

    @Test
    void weakTopicsGetTwiceTheShare() {
        List<PoolQuestion> pool = new ArrayList<>(mixed(A, 10));
        pool.addAll(mixed(B, 10));
        Context ctx = new Context(java.util.Map.of(), java.util.Map.of(A, 2), Set.of(), Set.of(), false);
        List<PoolQuestion> chosen = QuestionPicker.pick(pool, ctx, Size.count(12), 30, false, new Random(5)).questions();
        assertThat(chosen.stream().filter(x -> x.topicId().equals(A))).hasSize(8);
    }

    static List<PoolQuestion> pool(UUID topic, int count, int seconds) {
        List<PoolQuestion> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(q(topic, seconds));
        }
        return list;
    }

    @RepeatedTest(20)
    void chosenTopicsTakeTurnsAndNoQuestionRepeats() {
        List<PoolQuestion> all = new ArrayList<>(pool(A, 10, 60));
        all.addAll(pool(B, 10, 60));
        all.addAll(pool(C, 2, 60));
        Picked picked = QuestionPicker.pick(all, Set.of(), Size.count(9), 30, true, new Random());
        assertThat(picked.questions()).hasSize(9).doesNotHaveDuplicates();
        // C has only two questions, so A and B share the rest: 2 + at least 3 + at least 3 … but never all from one topic.
        assertThat(picked.questions().stream().filter(x -> x.topicId().equals(C))).hasSize(2);
        assertThat(picked.questions().stream().filter(x -> x.topicId().equals(A)).count()).isBetween(3L, 4L);
    }

    @Test
    void recentlySeenQuestionsAreUsedOnlyWhenTheRestRunOut() {
        List<PoolQuestion> all = pool(A, 6, 60);
        Set<UUID> seen = all.subList(0, 4).stream().map(PoolQuestion::questionId).collect(Collectors.toSet());
        Picked two = QuestionPicker.pick(all, seen, Size.count(2), 30, true, new Random());
        assertThat(two.questions()).noneMatch(x -> seen.contains(x.questionId()));
        Picked five = QuestionPicker.pick(all, seen, Size.count(5), 30, true, new Random());
        assertThat(five.questions().stream().filter(x -> !seen.contains(x.questionId()))).hasSize(2);
    }

    @Test
    void timeBasedSessionsAddQuestionsWhileTheirEstimatesFit() {
        List<PoolQuestion> all = new ArrayList<>(pool(A, 3, 120));
        all.addAll(pool(B, 10, 40));
        Picked picked = QuestionPicker.pick(all, Set.of(), Size.time(300), 30, false, new Random(1));
        assertThat(picked.estimatedSeconds()).isLessThanOrEqualTo(300).isGreaterThan(300 - 40);
        assertThat(picked.questions().stream().mapToInt(PoolQuestion::estSeconds).sum()).isEqualTo(picked.estimatedSeconds());
    }

    @Test
    void theMaximumAndTheAvailableQuestionsLimitTheSession() {
        assertThat(QuestionPicker.pick(pool(A, 50, 30), Set.of(), Size.count(40), 30, true, new Random()).questions()).hasSize(30);
        assertThat(QuestionPicker.pick(pool(A, 50, 10), Set.of(), Size.time(3600), 30, true, new Random()).questions()).hasSize(30);
        assertThat(QuestionPicker.pick(pool(A, 4, 30), Set.of(), Size.count(10), 30, true, new Random()).questions()).hasSize(4);
        assertThat(QuestionPicker.pick(List.of(), Set.of(), Size.count(10), 30, true, new Random()).questions()).isEmpty();
        // Every question is longer than the time chosen: one is still given.
        assertThat(QuestionPicker.pick(pool(A, 3, 200), Set.of(), Size.time(60), 30, true, new Random()).questions()).hasSize(1);
    }

    @Test
    void optionsAreShuffledOnlyWhenTheModeSaysSo() {
        List<String> labels = List.of("A", "B", "C", "D", "E");
        assertThat(QuestionPicker.optionOrder(labels, false, new Random())).isEqualTo(labels);
        Set<List<String>> orders = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            orders.add(QuestionPicker.optionOrder(labels, true, new Random(i)));
        }
        assertThat(orders).hasSizeGreaterThan(1).allSatisfy(o -> assertThat(o).containsExactlyInAnyOrderElementsOf(labels));
    }
}
