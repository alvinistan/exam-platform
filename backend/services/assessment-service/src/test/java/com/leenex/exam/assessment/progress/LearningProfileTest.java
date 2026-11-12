package com.leenex.exam.assessment.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.leenex.exam.assessment.progress.ProgressRepository.Answer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Turning marked answers into levels, weak topics and questions to ask again. */
class LearningProfileTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 8, 0, 0, 0, ZoneOffset.UTC);
    final UUID body = UUID.randomUUID();
    final UUID light = UUID.randomUUID();
    final List<Answer> answers = new ArrayList<>();

    void answer(UUID topic, UUID question, boolean correct, int hoursAgo) {
        answers.add(new Answer(topic, topic.equals(body) ? "Human Body" : "Reflection of Light", question, "MEDIUM",
                correct, NOW.minusHours(hoursAgo), UUID.randomUUID()));
    }

    @Test
    void levelsAndWeakTopicsComeFromEachTopicsOwnAnswers() {
        for (int i = 0; i < 5; i++) {
            answer(body, UUID.randomUUID(), true, 50 - i);
            answer(light, UUID.randomUUID(), false, 50 - i);
        }
        LearningProfile p = LearningProfile.from(answers, NOW);
        assertThat(p.level(body)).isEqualTo(3);
        assertThat(p.level(light)).isEqualTo(1);
        assertThat(p.level(UUID.randomUUID())).isEqualTo(2);
        assertThat(p.weakTopics()).containsExactly(light);
        assertThat(p.topics().get(light).state().status()).isEqualTo("NEEDS_WORK");
    }

    @Test
    void aQuestionAnsweredWrongComesBackAfterADayUnlessItWasLaterAnsweredRight() {
        UUID wrongYesterday = UUID.randomUUID();
        UUID wrongThenRight = UUID.randomUUID();
        UUID wrongAnHourAgo = UUID.randomUUID();
        answer(body, wrongYesterday, false, 30);
        answer(body, wrongThenRight, false, 40);
        answer(body, wrongThenRight, true, 26);
        answer(body, wrongAnHourAgo, false, 1);
        LearningProfile p = LearningProfile.from(answers, NOW);
        assertThat(p.retryDue()).containsExactly(wrongYesterday);
        // Two recent mistakes on one topic mark it, even before there is enough evidence to change its level.
        assertThat(p.repeatedMistakes()).containsExactly(body);
    }
}
