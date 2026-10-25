package com.leenex.exam.assessment.attempt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Set;

/**
 * Authoritative scoring (SC-1 … SC-6), as plain functions:
 * <ul>
 *   <li>A single-answer question earns its marks when the one correct option is chosen.</li>
 *   <li>A multi-answer question is all or nothing: exactly the correct options, no more and no fewer.</li>
 *   <li>A wrong answer loses {@code negativeMark × marks} (0 unless the exam sets negative marking).</li>
 *   <li>An unanswered question scores 0. The total is never below 0.</li>
 * </ul>
 */
public final class Scoring {

    private Scoring() {
    }

    /** {@code correct} is null for an unanswered question. */
    public record Mark(Boolean correct, BigDecimal awarded) {
    }

    public static Mark mark(Set<String> correctLabels, Set<String> selected, BigDecimal marks, BigDecimal negativeMark) {
        if (selected.isEmpty()) {
            return new Mark(null, BigDecimal.ZERO.setScale(2));
        }
        if (selected.equals(correctLabels)) {
            return new Mark(true, marks.setScale(2, RoundingMode.HALF_UP));
        }
        return new Mark(false, negativeMark.multiply(marks).negate().setScale(2, RoundingMode.HALF_UP));
    }

    public static BigDecimal total(Collection<BigDecimal> awarded) {
        BigDecimal sum = awarded.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.signum() < 0 ? BigDecimal.ZERO.setScale(2) : sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Whole-number percentage of {@code score} out of {@code max} (0 when there is nothing to score). */
    public static int percent(BigDecimal score, BigDecimal max) {
        if (max.signum() == 0) {
            return 0;
        }
        return score.multiply(BigDecimal.valueOf(100)).divide(max, 0, RoundingMode.HALF_UP).intValue();
    }
}
