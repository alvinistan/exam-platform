package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.client.CatalogClient.PoolQuestion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses the questions for a session (SG-1 … SG-6). Plain functions with an injected {@link Random}, so the rules
 * are easy to test:
 *
 * <ol>
 *   <li>Questions are grouped by topic and the topics take turns, so a session on three topics covers all three.</li>
 *   <li>Within a topic the order is random, but questions the student saw recently (default 7 days) come last, so
 *       they are repeated only when the pool runs out.</li>
 *   <li>By count: the first {@code count} questions in that order. By time: questions are added while their
 *       estimated solving times still fit the minutes chosen (each question has its own estimate).</li>
 *   <li>Never more than the mode's maximum, never the same question twice.</li>
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

    public record Picked(List<PoolQuestion> questions, int estimatedSeconds) {
    }

    public static Picked pick(List<PoolQuestion> pool, Set<UUID> recentlySeen, Size size, int maxCount, boolean shuffle,
                              Random random) {
        List<PoolQuestion> order = turnOrder(pool, recentlySeen, random);
        List<PoolQuestion> chosen = new ArrayList<>();
        int total = 0;
        int limit = size.byTime() ? maxCount : Math.min(size.count(), maxCount);
        for (PoolQuestion q : order) {
            if (chosen.size() >= limit) {
                break;
            }
            if (size.byTime() && total + q.estSeconds() > size.seconds()) {
                continue; // a shorter question further on may still fit
            }
            chosen.add(q);
            total += q.estSeconds();
        }
        if (chosen.isEmpty() && !order.isEmpty() && limit > 0) {
            // Every question is longer than the time chosen: give one rather than nothing.
            chosen.add(order.getFirst());
            total = order.getFirst().estSeconds();
        }
        if (shuffle) {
            Collections.shuffle(chosen, random);
        }
        return new Picked(List.copyOf(chosen), total);
    }

    /** Topics take turns; inside a topic, unseen questions in random order first, then recently seen ones. */
    static List<PoolQuestion> turnOrder(List<PoolQuestion> pool, Set<UUID> recentlySeen, Random random) {
        Map<UUID, List<PoolQuestion>> byTopic = new LinkedHashMap<>();
        // Sort first so the result depends only on the pool's contents and the random seed, not its order.
        List<PoolQuestion> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparing(PoolQuestion::questionId));
        for (PoolQuestion q : sorted) {
            byTopic.computeIfAbsent(q.topicId(), k -> new ArrayList<>()).add(q);
        }
        List<Deque<PoolQuestion>> queues = new ArrayList<>();
        for (List<PoolQuestion> questions : byTopic.values()) {
            Collections.shuffle(questions, random);
            questions.sort(Comparator.comparing(q -> recentlySeen.contains(q.questionId()))); // stable: false first
            queues.add(new ArrayDeque<>(questions));
        }
        Collections.shuffle(queues, random);
        List<PoolQuestion> order = new ArrayList<>(pool.size());
        boolean any = true;
        while (any) {
            any = false;
            for (Deque<PoolQuestion> queue : queues) {
                PoolQuestion next = queue.pollFirst();
                if (next != null) {
                    order.add(next);
                    any = true;
                }
            }
        }
        return order;
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
