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

    /** Days of activity shown on the dashboard. */
    static final int ACTIVITY_DAYS = 14;
    /** The longest streak counted; longer ones show as this many days. */
    static final int STREAK_LIMIT = 60;
    static final int FOCUS_LIMIT = 6;
    static final int FOCUS_EXAMS = 10;

    public record FocusTopic(UUID examId, String examTitle, String examSlug, UUID topicId, String title, int level,
                             String levelLabel, int recentAccuracy) {
    }

    public record ActivityDay(java.time.LocalDate day, int answered, int correct) {
    }

    /**
     * The dashboard's picture of the student across all exams.
     *
     * @param streakDays consecutive days up to today (or yesterday, if nothing yet today) with at least one marked
     *                   answer, in the student's time zone; at most {@value #STREAK_LIMIT}
     * @param activity   the last {@value #ACTIVITY_DAYS} days, oldest first, including days without answers
     * @param focus      the weakest topics across the exams practised most recently, weakest first
     */
    public record Overview(int answered, int correct, int accuracy, int finishedAttempts, int streakDays,
                           int answeredThisWeek, List<ActivityDay> activity, List<FocusTopic> focus,
                           List<ExamSummary> exams) {
    }

    public Overview overview(UUID studentId, java.time.ZoneId zone) {
        ProgressRepository.Totals totals = repository.totals(studentId);
        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(zone));
        java.time.LocalDate from = today.minusDays(STREAK_LIMIT);
        java.util.Map<java.time.LocalDate, ProgressRepository.Day> byDay = new java.util.HashMap<>();
        for (ProgressRepository.Day d : repository.activity(studentId, zone,
                from.atStartOfDay(zone).toOffsetDateTime())) {
            byDay.put(d.day(), d);
        }
        List<ActivityDay> activity = new ArrayList<>();
        int week = 0;
        for (int i = ACTIVITY_DAYS - 1; i >= 0; i--) {
            java.time.LocalDate day = today.minusDays(i);
            ProgressRepository.Day d = byDay.get(day);
            activity.add(new ActivityDay(day, d == null ? 0 : d.answered(), d == null ? 0 : d.correct()));
            if (i < 7 && d != null) {
                week += d.answered();
            }
        }
        // A streak isn't broken by not having practised yet today.
        java.time.LocalDate cursor = byDay.containsKey(today) ? today : today.minusDays(1);
        int streak = 0;
        while (streak < STREAK_LIMIT && byDay.containsKey(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }

        List<ExamSummary> exams = new ArrayList<>();
        List<FocusTopic> focus = new ArrayList<>();
        int examIndex = 0;
        for (ExamPractised e : repository.exams(studentId)) {
            ExamProgress p = exam(studentId, e.examId());
            exams.add(new ExamSummary(e.examId(), e.title(), e.slug(), e.lastActivity(), e.attempts(), p.answered(),
                    p.accuracy(), p.focus().size()));
            if (examIndex++ < FOCUS_EXAMS) {
                for (UUID topicId : p.focus()) {
                    p.topics().stream().filter(t -> t.topicId().equals(topicId)).findFirst().ifPresent(t ->
                            focus.add(new FocusTopic(e.examId(), e.title(), e.slug(), t.topicId(), t.title(), t.level(),
                                    t.levelLabel(), t.recentAccuracy())));
                }
            }
        }
        focus.sort(Comparator.comparingInt(FocusTopic::recentAccuracy));
        int accuracy = totals.answered() == 0 ? 0 : Math.round(100f * totals.correct() / totals.answered());
        return new Overview(totals.answered(), totals.correct(), accuracy, totals.finishedAttempts(), streak, week,
                activity, focus.stream().limit(FOCUS_LIMIT).toList(), exams);
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
