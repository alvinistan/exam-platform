package com.leenex.exam.assessment.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The adaptive rules for one student and one topic, as plain functions (ADR-016). Deterministic and easy to test:
 * the same answers always give the same level.
 *
 * <ul>
 *   <li>Every student starts each topic at level 2 (Medium), a balanced mix.</li>
 *   <li>The level moves only on evidence: at least {@value #MIN_EVIDENCE} answers since the last change, judged on
 *       the most recent {@value #WINDOW} of them.</li>
 *   <li>{@value #UP_PCT}% or more correct: one level up (more Hard questions). Below {@value #DOWN_PCT}%: one level
 *       down (more Easy questions, to rebuild the basics). After a change, the count starts again, so one bad answer
 *       never swings the level.</li>
 *   <li>Status, from the last {@value #WINDOW} answers: strong at 80% or more, needs work below 50%, otherwise
 *       developing; fewer than {@value #MIN_EVIDENCE} answers is "not enough yet".</li>
 * </ul>
 */
public final class TopicLevels {

    public static final int START_LEVEL = 2;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 3;
    public static final int WINDOW = 8;
    public static final int MIN_EVIDENCE = 5;
    public static final int UP_PCT = 80;
    public static final int DOWN_PCT = 40;
    public static final int STRONG_PCT = 80;
    public static final int WEAK_PCT = 50;

    public static final List<String> DIFFICULTIES = List.of("SIMPLE", "MEDIUM", "HARD");

    private TopicLevels() {
    }

    /**
     * @param level          the current level, 1 (Easy) to 3 (Hard)
     * @param answered       every counted answer on the topic
     * @param correct        of which correct
     * @param recentAnswered the last answers (at most {@value #WINDOW})
     * @param recentCorrect  of which correct
     * @param changes        level moves so far (+1 up, −1 down), oldest first, for the "trend"
     */
    public record State(int level, int answered, int correct, int recentAnswered, int recentCorrect, List<Integer> changes) {

        public int accuracy() {
            return answered == 0 ? 0 : Math.round(100f * correct / answered);
        }

        public int recentAccuracy() {
            return recentAnswered == 0 ? 0 : Math.round(100f * recentCorrect / recentAnswered);
        }

        public String status() {
            if (answered < MIN_EVIDENCE) {
                return "NOT_ENOUGH";
            }
            int recent = recentAccuracy();
            return recent >= STRONG_PCT ? "STRONG" : recent < WEAK_PCT ? "NEEDS_WORK" : "DEVELOPING";
        }
    }

    /** Replays a topic's answers (oldest first; true = correct) and returns where the student stands. */
    public static State replay(List<Boolean> answers) {
        int level = START_LEVEL;
        Deque<Boolean> sinceChange = new ArrayDeque<>();
        Deque<Boolean> recent = new ArrayDeque<>();
        int correct = 0;
        List<Integer> changes = new java.util.ArrayList<>();
        for (boolean a : answers) {
            if (a) {
                correct++;
            }
            push(recent, a);
            push(sinceChange, a);
            if (sinceChange.size() >= MIN_EVIDENCE) {
                long right = sinceChange.stream().filter(Boolean::booleanValue).count();
                int pct = Math.round(100f * right / sinceChange.size());
                if (pct >= UP_PCT && level < MAX_LEVEL) {
                    level++;
                    changes.add(1);
                    sinceChange.clear();
                } else if (pct < DOWN_PCT && level > MIN_LEVEL) {
                    level--;
                    changes.add(-1);
                    sinceChange.clear();
                }
            }
        }
        int recentCorrect = (int) recent.stream().filter(Boolean::booleanValue).count();
        return new State(level, answers.size(), correct, recent.size(), recentCorrect, List.copyOf(changes));
    }

    private static void push(Deque<Boolean> window, boolean answer) {
        window.addLast(answer);
        if (window.size() > WINDOW) {
            window.removeFirst();
        }
    }

    /**
     * How often each difficulty is aimed for at a level (percent; SIMPLE, MEDIUM, HARD). Level 1 rebuilds the basics,
     * level 2 is balanced, level 3 stretches the student. When a difficulty has no questions left the nearest one is
     * used instead.
     */
    public static Map<String, Integer> mix(int level) {
        return switch (level) {
            case 1 -> Map.of("SIMPLE", 70, "MEDIUM", 30, "HARD", 0);
            case 3 -> Map.of("SIMPLE", 0, "MEDIUM", 40, "HARD", 60);
            default -> Map.of("SIMPLE", 25, "MEDIUM", 50, "HARD", 25);
        };
    }

    /** Easy, Medium or Hard: how a level is shown to students. */
    public static String label(int level) {
        return switch (level) {
            case 1 -> "Easy";
            case 3 -> "Hard";
            default -> "Medium";
        };
    }
}
