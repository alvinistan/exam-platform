package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.client.CatalogClient.PoolQuestion;
import com.leenex.exam.assessment.progress.TopicLevels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses questions (SG-1 … SG-6 and the adaptive rules of ADR-016). Plain functions with an injected
 * {@link Random}, so the rules are easy to test. Each question is chosen in three steps:
 *
 * <ol>
 *   <li><b>Topic.</b> Topics take turns in proportion to their weight (weak topics in an Adaptive exam count
 *       double), so a session on three topics covers all three.</li>
 *   <li><b>Difficulty.</b> Aimed at the student's level on that topic: level 1 mostly Easy, level 2 a balanced mix,
 *       level 3 mostly Hard ({@link TopicLevels#mix}). If that difficulty has run out, the nearest one is used.</li>
 *   <li><b>Question.</b> When asked to, a question the student got wrong last time comes first (at most about a
 *       quarter of the session); then questions not seen recently; questions seen recently only when nothing else
 *       is left. Never the same question twice in a session.</li>
 * </ol>
 */
public final class QuestionPicker {

    private QuestionPicker() {
    }

    /** {@code count} for COUNT sessions, {@code seconds} for TIME sessions. */
    public record Size(String by, int count, int seconds) {

        public static Size count(int count) {
            return new Size("COUNT", count, 0);
        }

        public static Size time(int seconds) {
            return new Size("TIME", 0, seconds);
        }

        public boolean byTime() {
            return "TIME".equals(by);
        }
    }

    /**
     * What is known about the student.
     *
     * @param levels       level per topic (missing: {@link TopicLevels#START_LEVEL})
     * @param topicWeights how much of the session a topic should get relative to others (missing: 1)
     * @param recentlySeen questions given to the student recently
     * @param retryDue     questions answered wrong last time, due to be asked again
     * @param preferRetry  put {@code retryDue} questions first (Practice and Adaptive; never a Mock exam)
     */
    public record Context(Map<UUID, Integer> levels, Map<UUID, Integer> topicWeights, Set<UUID> recentlySeen,
                          Set<UUID> retryDue, boolean preferRetry) {

        public static Context plain(Set<UUID> recentlySeen) {
            return new Context(Map.of(), Map.of(), recentlySeen, Set.of(), false);
        }

        int level(UUID topic) {
            return levels.getOrDefault(topic, TopicLevels.START_LEVEL);
        }

        int weight(UUID topic) {
            return Math.max(1, topicWeights.getOrDefault(topic, 1));
        }
    }

    /** A chosen question and the level it was chosen for. */
    public record Choice(PoolQuestion question, int level) {
    }

    /** @param levels the level each question was chosen for, by question ID */
    public record Picked(List<PoolQuestion> questions, Map<UUID, Integer> levels, int estimatedSeconds) {
    }

    /** A whole question set: Practice and Mock exams, and the planned length of an Adaptive exam. */
    public static Picked pick(List<PoolQuestion> pool, Context ctx, Size size, int maxCount, boolean shuffle, Random random) {
        List<PoolQuestion> chosen = new ArrayList<>();
        Map<UUID, Integer> levels = new LinkedHashMap<>();
        int total = 0;
        int limit = size.byTime() ? maxCount : Math.min(size.count(), maxCount);
        while (chosen.size() < limit) {
            Integer budget = size.byTime() ? size.seconds() - total : null;
            Optional<Choice> next = next(pool, chosen, ctx, budget, random);
            if (next.isEmpty()) {
                break;
            }
            chosen.add(next.get().question());
            levels.put(next.get().question().questionId(), next.get().level());
            total += next.get().question().estSeconds();
        }
        if (chosen.isEmpty() && limit > 0) {
            // Every question is longer than the time chosen: give the shortest rather than nothing.
            Optional<PoolQuestion> shortest = pool.stream().min(Comparator.comparingInt(PoolQuestion::estSeconds));
            if (shortest.isPresent()) {
                chosen.add(shortest.get());
                levels.put(shortest.get().questionId(), ctx.level(shortest.get().topicId()));
                total = shortest.get().estSeconds();
            }
        }
        if (shuffle) {
            Collections.shuffle(chosen, random);
        }
        return new Picked(List.copyOf(chosen), levels, total);
    }

    /** Step 1 behaviour kept for callers that know nothing about the student. */
    public static Picked pick(List<PoolQuestion> pool, Set<UUID> recentlySeen, Size size, int maxCount, boolean shuffle,
                              Random random) {
        return pick(pool, Context.plain(recentlySeen), size, maxCount, shuffle, random);
    }

    /**
     * The next question after {@code chosen}, or empty when none is left (or none fits {@code secondsLeft}). An
     * Adaptive exam calls this after every answer, with a context that already includes that answer.
     */
    public static Optional<Choice> next(List<PoolQuestion> pool, List<PoolQuestion> chosen, Context ctx,
                                        Integer secondsLeft, Random random) {
        Set<UUID> used = new HashSet<>();
        Map<UUID, Integer> perTopic = new HashMap<>();
        int retries = 0;
        for (PoolQuestion q : chosen) {
            used.add(q.questionId());
            perTopic.merge(q.topicId(), 1, Integer::sum);
            if (ctx.retryDue().contains(q.questionId())) {
                retries++;
            }
        }
        // Sort first, so the outcome depends only on the pool's contents and the random seed, not its order.
        List<PoolQuestion> left = pool.stream()
                .filter(q -> !used.contains(q.questionId()))
                .filter(q -> secondsLeft == null || q.estSeconds() <= secondsLeft)
                .sorted(Comparator.comparing(PoolQuestion::questionId))
                .toList();
        if (left.isEmpty()) {
            return Optional.empty();
        }

        // 1. Topic: the one furthest behind its share (ties broken at random).
        Map<UUID, List<PoolQuestion>> byTopic = new LinkedHashMap<>();
        left.forEach(q -> byTopic.computeIfAbsent(q.topicId(), k -> new ArrayList<>()).add(q));
        double best = Double.MAX_VALUE;
        List<UUID> behind = new ArrayList<>();
        for (UUID topic : byTopic.keySet()) {
            double share = (perTopic.getOrDefault(topic, 0) + 1.0) / ctx.weight(topic);
            if (share < best - 1e-9) {
                best = share;
                behind.clear();
                behind.add(topic);
            } else if (Math.abs(share - best) < 1e-9) {
                behind.add(topic);
            }
        }
        UUID topic = behind.get(random.nextInt(behind.size()));

        // 2. Difficulty: drawn from the level's mix.
        int level = ctx.level(topic);
        String target = draw(TopicLevels.mix(level), random);
        int targetIndex = TopicLevels.DIFFICULTIES.indexOf(target);

        // 3. Question: retry first (within its quarter), then unseen, then seen; nearest difficulty; then at random.
        boolean retryAllowed = ctx.preferRetry() && retries < 1 + chosen.size() / 4;
        List<PoolQuestion> candidates = new ArrayList<>(byTopic.get(topic));
        Collections.shuffle(candidates, random);
        candidates.sort(Comparator
                .comparingInt((PoolQuestion q) -> tier(q, ctx, retryAllowed))
                .thenComparingInt(q -> Math.abs(TopicLevels.DIFFICULTIES.indexOf(q.difficulty()) - targetIndex)));
        return Optional.of(new Choice(candidates.getFirst(), level));
    }

    private static int tier(PoolQuestion q, Context ctx, boolean retryAllowed) {
        if (retryAllowed && ctx.retryDue().contains(q.questionId())) {
            return 0;
        }
        return ctx.recentlySeen().contains(q.questionId()) ? 2 : 1;
    }

    private static String draw(Map<String, Integer> mix, Random random) {
        int total = mix.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(Math.max(1, total));
        for (String difficulty : TopicLevels.DIFFICULTIES) {
            roll -= mix.getOrDefault(difficulty, 0);
            if (roll < 0) {
                return difficulty;
            }
        }
        return "MEDIUM";
    }

    /** The display order of a question's options (labels as stored), shuffled when the mode says so. */
    public static List<String> optionOrder(List<String> labels, boolean shuffle, Random random) {
        List<String> order = new ArrayList<>(labels);
        if (shuffle) {
            Collections.shuffle(order, random);
        }
        return List.copyOf(order);
    }
}
