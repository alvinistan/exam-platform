package com.leenex.exam.assessment.progress;

import com.leenex.exam.assessment.progress.LearningProfile.TopicProfile;
import com.leenex.exam.assessment.progress.ProgressRepository.ExamPractised;
import com.leenex.exam.assessment.progress.ProgressRepository.Session;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The student's performance profile and "My progress" (ADR-016). */
@Service
public class ProgressService {

    static final int SESSION_LIMIT = 20;

    private final ProgressRepository repository;
    private final Clock clock;

    public ProgressService(ProgressRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LearningProfile profile(UUID studentId, UUID examId) {
        return LearningProfile.from(repository.answers(studentId, examId), now());
    }

    public record TopicProgress(UUID topicId, String title, int answered, int correct, int accuracy, int recentAccuracy,
                                int level, String levelLabel, String status, int levelTrend, OffsetDateTime lastPracticed) {
    }

    public record SessionPoint(UUID attemptId, String mode, OffsetDateTime finishedAt, int percent, int questions) {
    }

    /**
     * @param focus the topics to practise next: those that need work or keep getting the same mistakes, weakest first
     */
    public record ExamProgress(UUID examId, int answered, int correct, int accuracy, List<TopicProgress> topics,
                               List<UUID> focus, List<SessionPoint> sessions) {
    }

    public record ExamSummary(UUID examId, String title, String slug, OffsetDateTime lastActivity, int attempts,
                              int answered, int accuracy, int topicsNeedingWork) {
    }

    public ExamProgress exam(UUID studentId, UUID examId) {
        LearningProfile profile = profile(studentId, examId);
        List<TopicProgress> topics = new ArrayList<>();
        int answered = 0;
        int correct = 0;
        for (TopicProfile t : profile.topics().values()) {
            TopicLevels.State s = t.state();
            answered += s.answered();
            correct += s.correct();
            int trend = s.changes().isEmpty() ? 0 : s.changes().getLast();
            topics.add(new TopicProgress(t.topicId(), t.title(), s.answered(), s.correct(), s.accuracy(),
                    s.recentAccuracy(), s.level(), TopicLevels.label(s.level()), s.status(), trend, t.lastPracticed()));
        }
        topics.sort(Comparator.comparing(TopicProgress::title));
        List<UUID> focus = topics.stream()
                .filter(t -> profile.weakTopics().contains(t.topicId()))
                .sorted(Comparator.comparingInt(TopicProgress::recentAccuracy))
                .map(TopicProgress::topicId).toList();
        List<SessionPoint> sessions = repository.sessions(studentId, examId, SESSION_LIMIT).stream()
                .map(ProgressService::point).toList();
        return new ExamProgress(examId, answered, correct, answered == 0 ? 0 : Math.round(100f * correct / answered),
                topics, focus, sessions);
    }

    public List<ExamSummary> exams(UUID studentId) {
        List<ExamSummary> out = new ArrayList<>();
        for (ExamPractised e : repository.exams(studentId)) {
            ExamProgress p = exam(studentId, e.examId());
            out.add(new ExamSummary(e.examId(), e.title(), e.slug(), e.lastActivity(), e.attempts(), p.answered(),
                    p.accuracy(), p.focus().size()));
        }
        return out;
    }

    private static SessionPoint point(Session s) {
        int percent = s.maxScore() == null || s.maxScore().signum() == 0 ? 0
                : s.score().multiply(java.math.BigDecimal.valueOf(100))
                        .divide(s.maxScore(), 0, java.math.RoundingMode.HALF_UP).intValue();
        return new SessionPoint(s.attemptId(), s.mode(), s.finishedAt(), percent, s.questionCount());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
