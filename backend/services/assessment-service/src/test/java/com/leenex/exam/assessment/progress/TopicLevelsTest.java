package com.leenex.exam.assessment.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The adaptive rules (ADR-016): start at Medium, move one level on enough evidence, never on one answer. */
class TopicLevelsTest {

    static List<Boolean> answers(String pattern) {
        List<Boolean> list = new ArrayList<>();
        for (char c : pattern.toCharArray()) {
            list.add(c == '1');
        }
        return list;
    }

    @Test
    void everyoneStartsAtMediumAndOneAnswerChangesNothing() {
        assertThat(TopicLevels.replay(List.of()).level()).isEqualTo(2);
        assertThat(TopicLevels.replay(answers("0")).level()).isEqualTo(2);
        assertThat(TopicLevels.replay(answers("0000")).level()).isEqualTo(2); // four is not enough evidence
        assertThat(TopicLevels.replay(answers("0")).status()).isEqualTo("NOT_ENOUGH");
    }

    @Test
    void strongAnswersMoveUpAndWeakOnesMoveDown() {
        assertThat(TopicLevels.replay(answers("11111")).level()).isEqualTo(3);
        assertThat(TopicLevels.replay(answers("11011")).level()).isEqualTo(3); // 80%
        assertThat(TopicLevels.replay(answers("00000")).level()).isEqualTo(1);
        assertThat(TopicLevels.replay(answers("01000")).level()).isEqualTo(1); // 20%
        assertThat(TopicLevels.replay(answers("10101")).level()).isEqualTo(2); // 60%: stays
    }

    @Test
    void theCountStartsAgainAfterAChangeSoLevelsMoveOneStepAtATime() {
        // Five right: up to Hard. Then four wrong is not yet enough to come down.
        assertThat(TopicLevels.replay(answers("11111" + "0000")).level()).isEqualTo(3);
        // A fifth wrong answer is: back to Medium, not straight to Easy.
        TopicLevels.State state = TopicLevels.replay(answers("11111" + "00000"));
        assertThat(state.level()).isEqualTo(2);
        assertThat(state.changes()).containsExactly(1, -1);
        // Improving again gradually raises the level.
        assertThat(TopicLevels.replay(answers("00000" + "11111" + "11111")).level()).isEqualTo(3);
    }

    @Test
    void statusUsesTheLastEightAnswers() {
        TopicLevels.State strong = TopicLevels.replay(answers("0000" + "11111111"));
        assertThat(strong.recentAccuracy()).isEqualTo(100);
        assertThat(strong.accuracy()).isEqualTo(67);
        assertThat(strong.status()).isEqualTo("STRONG");
        assertThat(TopicLevels.replay(answers("11110000")).status()).isEqualTo("DEVELOPING"); // 50%
        assertThat(TopicLevels.replay(answers("11000000")).status()).isEqualTo("NEEDS_WORK");
        assertThat(TopicLevels.replay(Collections.nCopies(50, true)).level()).isEqualTo(3);
    }

    @Test
    void eachLevelHasItsMixOfDifficulties() {
        assertThat(TopicLevels.mix(1)).containsEntry("SIMPLE", 70).containsEntry("HARD", 0);
        assertThat(TopicLevels.mix(2)).containsEntry("MEDIUM", 50);
        assertThat(TopicLevels.mix(3)).containsEntry("HARD", 60).containsEntry("SIMPLE", 0);
        for (int level = 1; level <= 3; level++) {
            assertThat(TopicLevels.mix(level).values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
        }
        assertThat(TopicLevels.label(1)).isEqualTo("Easy");
    }
}
