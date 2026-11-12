package com.leenex.exam.assessment.attempt;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Scoring rules SC-1 … SC-6. */
class ScoringTest {

    static final BigDecimal ONE = BigDecimal.ONE;
    static final BigDecimal NONE = BigDecimal.ZERO;

    @Test
    void aSingleAnswerEarnsItsMarksWhenRight() {
        assertThat(Scoring.mark(Set.of("B"), Set.of("B"), new BigDecimal("2"), NONE))
                .isEqualTo(new Scoring.Mark(true, new BigDecimal("2.00")));
        assertThat(Scoring.mark(Set.of("B"), Set.of("C"), ONE, NONE)).isEqualTo(new Scoring.Mark(false, new BigDecimal("0.00")));
    }

    @Test
    void multiAnswerQuestionsAreAllOrNothing() {
        assertThat(Scoring.mark(Set.of("A", "C"), Set.of("A", "C"), ONE, NONE).correct()).isTrue();
        assertThat(Scoring.mark(Set.of("A", "C"), Set.of("A"), ONE, NONE).correct()).isFalse();
        assertThat(Scoring.mark(Set.of("A", "C"), Set.of("A", "B", "C"), ONE, NONE).correct()).isFalse();
    }

    @Test
    void unansweredScoresZeroAndNegativeMarkingNeverTakesTheTotalBelowZero() {
        assertThat(Scoring.mark(Set.of("A"), Set.of(), ONE, new BigDecimal("0.25")))
                .isEqualTo(new Scoring.Mark(null, new BigDecimal("0.00")));
        assertThat(Scoring.mark(Set.of("A"), Set.of("B"), ONE, new BigDecimal("0.25")).awarded())
                .isEqualByComparingTo("-0.25");
        assertThat(Scoring.total(List.of(new BigDecimal("-0.25"), new BigDecimal("-0.25")))).isEqualByComparingTo("0");
        assertThat(Scoring.total(List.of(ONE, new BigDecimal("-0.25")))).isEqualByComparingTo("0.75");
    }

    @Test
    void percentagesAreWholeNumbers() {
        assertThat(Scoring.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo(33);
        assertThat(Scoring.percent(new BigDecimal("2"), new BigDecimal("3"))).isEqualTo(67);
        assertThat(Scoring.percent(NONE, NONE)).isZero();
    }
}
