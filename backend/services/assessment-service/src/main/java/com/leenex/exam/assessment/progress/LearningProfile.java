package com.leenex.exam.assessment.progress;

import com.leenex.exam.assessment.progress.ProgressRepository.Answer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One student's standing on one exam, worked out from their marked answers: a level and status per topic, and the
 * questions they got wrong last time (ADR-016). Built fresh whenever it is needed, so it always reflects the latest
 * answer, including one given a moment ago in the same session.
 *
 * @param topics      per topic, in the order first practised
 * @param retryDue    questions whose latest answer was wrong, long enough ago to be worth asking again
 * @param repeatedMistakes topics with two or more wrong answers among the recent ones
 */
public record LearningProfile(Map<UUID, TopicProfile> topics, Set<UUID> retryDue, Set<UUID> repeatedMistakes) {

    /** A wrong answer can come back after this long (the usual repeat gap is longer: see recently-seen). */
    public static final Duration RETRY_AFTER = Duration.ofDays(1);

    public record TopicProfile(UUID topicId, String title, TopicLevels.State state, OffsetDateTime lastPracticed,
                               int recentWrong) {
    }

    public static final LearningProfile EMPTY = new LearningProfile(Map.of(), Set.of(), Set.of());

    public static LearningProfile from(List<Answer> oldestFirst, OffsetDateTime now) {
        Map<UUID, List<Boolean>> byTopic = new LinkedHashMap<>();
        Map<UUID, String> titles = new HashMap<>();
        Map<UUID, OffsetDateTime> last = new HashMap<>();
        Map<UUID, Answer> latestPerQuestion = new HashMap<>();
        for (Answer a : oldestFirst) {
            byTopic.computeIfAbsent(a.topicId(), k -> new ArrayList<>()).add(a.correct());
            titles.put(a.topicId(), a.topicTitle());
            last.put(a.topicId(), a.answeredAt());
            latestPerQuestion.put(a.questionId(), a);
        }
        Map<UUID, TopicProfile> topics = new LinkedHashMap<>();
        Set<UUID> repeated = new HashSet<>();
        byTopic.forEach((topic, answers) -> {
            TopicLevels.State state = TopicLevels.replay(answers);
            int recentWrong = state.recentAnswered() - state.recentCorrect();
            if (recentWrong >= 2) {
                repeated.add(topic);
            }
            topics.put(topic, new TopicProfile(topic, titles.get(topic), state, last.get(topic), recentWrong));
        });
        Set<UUID> retry = new HashSet<>();
        OffsetDateTime cutoff = now.minus(RETRY_AFTER);
        latestPerQuestion.forEach((question, a) -> {
            if (!a.correct() && !a.answeredAt().isAfter(cutoff)) {
                retry.add(question);
            }
        });
        return new LearningProfile(topics, retry, repeated);
    }

    /** The topic's current level, or the starting level for a topic not practised yet. */
    public int level(UUID topicId) {
        TopicProfile t = topics.get(topicId);
        return t == null ? TopicLevels.START_LEVEL : t.state().level();
    }

    public Map<UUID, Integer> levels() {
        Map<UUID, Integer> levels = new HashMap<>();
        topics.forEach((id, t) -> levels.put(id, t.state().level()));
        return levels;
    }

    /** Topics that need work, or where the same mistakes keep happening. */
    public Set<UUID> weakTopics() {
        Set<UUID> weak = new HashSet<>(repeatedMistakes);
        topics.forEach((id, t) -> {
            if ("NEEDS_WORK".equals(t.state().status())) {
                weak.add(id);
            }
        });
        return weak;
    }
}
