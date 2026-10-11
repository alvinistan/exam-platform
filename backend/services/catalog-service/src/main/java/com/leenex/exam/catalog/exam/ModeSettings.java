package com.leenex.exam.catalog.exam;

import java.math.BigDecimal;
import java.util.List;

/**
 * One exam mode's configuration (FR-CAT-07). {@code saved} is false while the administrator has not saved the mode
 * yet; the values are then the suggested defaults and the mode is disabled.
 */
public record ModeSettings(String mode, boolean enabled, int defaultCount, int minCount, int maxCount,
                           Integer timeLimitSeconds, Integer perQuestionSeconds, DifficultyMix difficultyMix,
                           boolean shuffleQuestions, boolean shuffleOptions, String feedbackTiming,
                           String resultVisibility, BigDecimal negativeMark, Integer passMarkPct,
                           boolean allowTopicChoice, boolean adaptiveEnabled, Integer maxRetakes, int version,
                           boolean saved) {

    public static final List<String> MODES = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");

    public record DifficultyMix(int simple, int medium, int hard) {
    }

    /** What an administrator sees for a mode that was never saved. */
    public static ModeSettings suggested(String mode) {
        DifficultyMix mix = new DifficultyMix(30, 50, 20);
        return switch (mode) {
            case "PRACTICE" -> new ModeSettings(mode, false, 10, 5, 50, null, null, mix, true, true, "IMMEDIATE", "FULL",
                    BigDecimal.ZERO.setScale(2), null, true, false, null, 0, false);
            case "MOCK" -> new ModeSettings(mode, false, 40, 10, 200, 3600, null, mix, true, true, "AFTER_SUBMIT", "FULL",
                    BigDecimal.ZERO.setScale(2), null, false, false, null, 0, false);
            case "USER_FRIENDLY" -> new ModeSettings(mode, false, 10, 5, 50, null, null, mix, true, true, "AFTER_SUBMIT",
                    "FULL", BigDecimal.ZERO.setScale(2), null, true, false, null, 0, false);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    /** Label used in messages to administrators; the web app uses the same names. */
    public static String label(String mode) {
        return switch (mode) {
            case "PRACTICE" -> "Practice";
            case "MOCK" -> "Mock exam";
            case "USER_FRIENDLY" -> "Guided practice";
            default -> mode;
        };
    }
}
