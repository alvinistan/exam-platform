package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.attempt.AttemptRepository.AttemptRow;
import com.leenex.exam.assessment.attempt.AttemptRepository.NewAttempt;
import com.leenex.exam.assessment.attempt.AttemptRepository.NewQuestion;
import com.leenex.exam.assessment.attempt.AttemptRepository.QuestionRow;
import com.leenex.exam.assessment.attempt.AttemptViews.AttemptSummary;
import com.leenex.exam.assessment.attempt.AttemptViews.AttemptView;
import com.leenex.exam.assessment.attempt.AttemptViews.OptionView;
import com.leenex.exam.assessment.attempt.AttemptViews.QuestionView;
import com.leenex.exam.assessment.attempt.AttemptViews.Result;
import com.leenex.exam.assessment.attempt.AttemptViews.Review;
import com.leenex.exam.assessment.attempt.AttemptViews.TopicRef;
import com.leenex.exam.assessment.attempt.AttemptViews.TopicResult;
import com.leenex.exam.assessment.attempt.QuestionPicker.Choice;
import com.leenex.exam.assessment.attempt.QuestionPicker.Context;
import com.leenex.exam.assessment.attempt.QuestionPicker.Picked;
import com.leenex.exam.assessment.attempt.QuestionPicker.Size;
import com.leenex.exam.assessment.client.AccessClient;
import com.leenex.exam.assessment.client.AccessClient.Decision;
import com.leenex.exam.assessment.client.CatalogClient;
import com.leenex.exam.assessment.client.CatalogClient.Delivered;
import com.leenex.exam.assessment.client.CatalogClient.DeliveredOption;
import com.leenex.exam.assessment.client.CatalogClient.ModeSettings;
import com.leenex.exam.assessment.client.CatalogClient.Pool;
import com.leenex.exam.assessment.client.CatalogClient.PoolOption;
import com.leenex.exam.assessment.client.CatalogClient.PoolQuestion;
import com.leenex.exam.assessment.client.CatalogClient.PoolTopic;
import com.leenex.exam.assessment.config.AssessmentProperties;
import com.leenex.exam.assessment.progress.LearningProfile;
import com.leenex.exam.assessment.progress.ProgressService;
import com.leenex.exam.assessment.progress.TopicLevels;
import com.leenex.exam.assessment.support.Requests;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exam flow for all three exam types (Practice, Mock exam, Adaptive exam):
 * start (topics + a question count or a time) → answer → submit → result and review.
 *
 * <ul>
 *   <li>Practice and Adaptive always check each answer as it is given (immediate feedback); a Mock exam does so only
 *       when the exam is set up that way, otherwise answers and explanations appear after submitting.</li>
 *   <li>Mock exams are timed on the server (ADR-010): the end time is fixed at start; answers more than the grace
 *       period late are refused and the attempt is finished as expired, by the next request or by the sweep.</li>
 *   <li>Every start is an access decision that counts one free attempt of that exam type (ADR-007).</li>
 *   <li>Scores are always worked out here, from the answer key copied at start (ADR-008).</li>
 * </ul>
 */
@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
    static final List<String> MODES = List.of("PRACTICE", "MOCK", "USER_FRIENDLY");
    private static final int HISTORY_LIMIT = 50;

    /** The mode settings copied onto the attempt at start, so later edits don't change a running attempt. */
    public record AttemptConfig(String feedback, BigDecimal negativeMark, Integer passMarkPct, Boolean shuffleOptions) {

        boolean immediate() {
            return "IMMEDIATE".equals(feedback);
        }
    }

    public record StartInput(UUID examId, String mode, List<UUID> topicIds, String sizeBy, Integer questionCount,
                             Integer minutes) {
    }

    public record AnswerInput(List<String> selected, Boolean flagged, Integer timeSpentMs) {
    }

    /** {@code created}: false when the same idempotency key returned an attempt started before. */
    public record Started(AttemptView view, boolean created) {
    }

    private final AttemptRepository attempts;
    private final CatalogClient catalog;
    private final AccessClient access;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final AssessmentProperties.Timing timing;
    private final Random random;
    private final ProgressService progress;

    public AttemptService(AttemptRepository attempts, CatalogClient catalog, AccessClient access, TransactionTemplate tx,
                          JsonMapper json, Clock clock, AssessmentProperties properties, ProgressService progress) {
        this.progress = progress;
        this.attempts = attempts;
        this.catalog = catalog;
        this.access = access;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.timing = properties.timing();
        this.random = new SecureRandom();
    }

    public static String label(String mode) {
        return switch (mode) {
            case "PRACTICE" -> "Practice";
            case "MOCK" -> "Mock exam";
            case "USER_FRIENDLY" -> "Adaptive exam";
            default -> mode;
        };
    }

    // --- start --------------------------------------------------------------------------------------------------------

    public Started start(UUID studentId, StartInput in, String idempotencyKey) {
        validate(in);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UuidV7.generate().toString() : idempotencyKey;
        if (key.length() > 100) {
            throw Requests.invalid("Idempotency-Key", "use at most 100 characters");
        }
        Optional<AttemptRow> replay = attempts.byKey(studentId, key);
        if (replay.isPresent()) {
            return new Started(view(studentId, replay.get().id()), false);
        }

        Pool pool = catalog.pool(in.examId(), List.of());
        if (!pool.published()) {
            throw new ApiException(ProblemCode.EXAM_UNAVAILABLE, "This exam isn't available at the moment.");
        }
        ModeSettings mode = pool.modes().stream().filter(m -> m.mode().equals(in.mode()) && m.enabled()).findFirst()
                .orElseThrow(() -> new ApiException(ProblemCode.MODE_DISABLED, label(in.mode()) + " isn't available for this exam."));
        Map<UUID, PoolTopic> examTopics = pool.topics().stream()
                .collect(Collectors.toMap(PoolTopic::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<UUID> chosenTopics = mode.allowTopicChoice() && in.topicIds() != null
                ? List.copyOf(new LinkedHashSet<>(in.topicIds())) : List.of();
        for (UUID topic : chosenTopics) {
            if (!examTopics.containsKey(topic)) {
                throw Requests.invalid("topicIds", "choose topics from this exam");
            }
        }
        Size size = size(in, mode);

        if ("MOCK".equals(in.mode())) {
            Optional<UUID> active = attempts.activeMock(studentId, in.examId());
            if (active.isPresent()) {
                finishIfDue(active.get());
                if (attempts.activeMock(studentId, in.examId()).isPresent()) {
                    throw new ApiException(ProblemCode.CONFLICT,
                            "You already have a mock exam in progress for this exam. Finish it first.",
                            Map.of("attemptId", active.get().toString()));
                }
            }
        }

        OffsetDateTime now = now();
        Set<UUID> seen = attempts.seenSince(studentId, in.examId(), now.minus(timing.recentlySeen()));
        List<PoolQuestion> usable = usable(pool, examTopics.keySet(), chosenTopics);
        Context ctx = context(in.mode(), progress.profile(studentId, in.examId()), seen);
        Picked picked = QuestionPicker.pick(usable, ctx, size, mode.maxCount(), mode.shuffleQuestions(), random);
        if (picked.questions().isEmpty()) {
            throw new ApiException(ProblemCode.INSUFFICIENT_QUESTIONS,
                    "There are no questions for the topics you chose yet. Choose other topics.");
        }

        OffsetDateTime expires = null;
        if ("MOCK".equals(in.mode())) {
            expires = now.plusSeconds(mockSeconds(size, picked, mode));
        }
        String feedback = "MOCK".equals(in.mode()) ? mode.feedbackTiming() : "IMMEDIATE";
        AttemptConfig config = new AttemptConfig(feedback,
                mode.negativeMark() == null ? BigDecimal.ZERO : mode.negativeMark(), mode.passMarkPct(),
                mode.shuffleOptions());

        UUID attemptId = UuidV7.generate();
        Decision decision = access.consume(studentId, in.examId(), in.mode(), attemptId);
        if (!decision.allowed()) {
            throw AccessClient.refusal(decision, label(in.mode()));
        }

        // A fixed set is given in full. An Adaptive exam plans its length the same way, then gets its first question
        // now and each further one after the previous answer (ADR-016).
        List<PoolQuestion> given = picked.questions();
        Map<UUID, Integer> levels = picked.levels();
        Integer planned = null;
        if (ADAPTIVE.equals(in.mode())) {
            planned = picked.questions().size();
            Choice first = QuestionPicker.next(usable, List.of(), ctx, size.byTime() ? size.seconds() : null, random)
                    .orElse(new Choice(picked.questions().getFirst(), ctx.levels().getOrDefault(
                            picked.questions().getFirst().topicId(), TopicLevels.START_LEVEL)));
            given = List.of(first.question());
            levels = Map.of(first.question().questionId(), first.level());
        }
        List<NewQuestion> questions = new ArrayList<>();
        int position = 1;
        for (PoolQuestion q : given) {
            questions.add(newQuestion(position++, q, examTopics.get(q.topicId()).title(), levels.get(q.questionId()),
                    mode.shuffleOptions()));
        }
        NewAttempt attempt = new NewAttempt(attemptId, studentId, in.examId(), pool.title(), pool.slug(), in.mode(),
                size.by(), size.byTime() ? null : size.count(), size.byTime() ? size.seconds() : null,
                decision.bank() == null ? "MAIN" : decision.bank(), decision.reason(), json.writeValueAsString(config),
                now, expires, key, chosenTopics, planned);
        try {
            tx.executeWithoutResult(s -> attempts.insert(attempt, questions));
        } catch (DuplicateKeyException e) {
            // A parallel start with the same key won, or a mock exam was started in parallel.
            Optional<AttemptRow> raced = attempts.byKey(studentId, key);
            if (raced.isPresent()) {
                return new Started(view(studentId, raced.get().id()), false);
            }
            throw new ApiException(ProblemCode.CONFLICT, "You already have a mock exam in progress for this exam.");
        }
        return new Started(view(studentId, attemptId), true);
    }

    static final String ADAPTIVE = "USER_FRIENDLY";

    /** Questions that can be given: in the exam's (or the chosen) topics, with options and an answer key. */
    private static List<PoolQuestion> usable(Pool pool, Set<UUID> examTopics, List<UUID> chosenTopics) {
        return pool.questions().stream()
                .filter(q -> chosenTopics.isEmpty() || chosenTopics.contains(q.topicId()))
                .filter(q -> examTopics.contains(q.topicId()))
                .filter(q -> q.options().size() >= 2 && q.options().stream().anyMatch(PoolOption::correct))
                .toList();
    }

    /**
     * What the picker knows about the student (ADR-016): levels per topic for every exam type; questions answered
     * wrong last time come first in Practice and Adaptive exams (never in a Mock exam); and in an Adaptive exam the
     * topics that need work get twice the share.
     */
    private static Context context(String mode, LearningProfile profile, Set<UUID> seen) {
        Map<UUID, Integer> weights = new HashMap<>();
        if (ADAPTIVE.equals(mode)) {
            profile.weakTopics().forEach(t -> weights.put(t, 2));
        }
        return new Context(profile.levels(), weights, seen, profile.retryDue(), !"MOCK".equals(mode));
    }

    private NewQuestion newQuestion(int position, PoolQuestion q, String topicTitle, Integer level, Boolean shuffle) {
        List<String> labels = q.options().stream().map(PoolOption::label).toList();
        Map<String, String> misconceptions = new LinkedHashMap<>();
        q.options().stream().filter(o -> o.misconceptionId() != null)
                .forEach(o -> misconceptions.put(o.label(), o.misconceptionId().toString()));
        return new NewQuestion(position, q.questionId(), q.versionId(), q.topicId(), topicTitle, q.difficulty(),
                q.estSeconds(), q.answerType(), QuestionPicker.optionOrder(labels, !Boolean.FALSE.equals(shuffle), random),
                q.options().stream().filter(PoolOption::correct).map(PoolOption::label).toList(),
                json.writeValueAsString(misconceptions), q.marks(), level);
    }

    /**
     * After an answer in an Adaptive exam: choose the next question with the student's levels as they are now,
     * including the answer just given. Runs inside the answer's transaction. If nothing suitable is left the exam
     * ends with the questions it has.
     */
    private void giveNextAdaptiveQuestion(AttemptRow row, OffsetDateTime now) {
        List<QuestionRow> given = attempts.questions(row.id());
        int planned = row.plannedCount() == null ? given.size() : row.plannedCount();
        if (given.size() >= planned) {
            return;
        }
        Pool pool = catalog.pool(row.examId(), row.topicIds());
        Map<UUID, PoolTopic> examTopics = pool.topics().stream()
                .collect(Collectors.toMap(PoolTopic::id, Function.identity(), (a, b) -> a));
        List<PoolQuestion> usable = usable(pool, examTopics.keySet(), row.topicIds());
        Map<UUID, PoolQuestion> byId = usable.stream().collect(Collectors.toMap(PoolQuestion::questionId, q -> q));
        List<PoolQuestion> chosen = given.stream().map(g -> byId.getOrDefault(g.questionId(),
                new PoolQuestion(g.questionId(), g.versionId(), g.topicId(), g.answerType(), g.difficulty(),
                        g.estSeconds(), g.marks(), List.of()))).toList();
        Set<UUID> seen = attempts.seenSince(row.studentId(), row.examId(), now.minus(timing.recentlySeen()));
        Context ctx = context(row.mode(), progress.profile(row.studentId(), row.examId()), seen);
        Integer budget = row.requestedSeconds() == null ? null
                : row.requestedSeconds() - given.stream().mapToInt(QuestionRow::estSeconds).sum();
        Optional<Choice> next = QuestionPicker.next(usable, chosen, ctx, budget, random);
        if (next.isEmpty() || !examTopics.containsKey(next.get().question().topicId())) {
            attempts.setPlanned(row.id(), given.size());
            return;
        }
        PoolQuestion q = next.get().question();
        attempts.insertQuestion(row.id(), newQuestion(given.size() + 1, q, examTopics.get(q.topicId()).title(),
                next.get().level(), config(row).shuffleOptions()));
    }

    private void validate(StartInput in) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (in.examId() == null) {
            errors.add(Map.of("field", "examId", "message", "is required"));
        }
        if (in.mode() == null || !MODES.contains(in.mode())) {
            errors.add(Map.of("field", "mode", "message", "must be PRACTICE, MOCK or USER_FRIENDLY"));
        }
        if (in.topicIds() != null && in.topicIds().size() > 100) {
            errors.add(Map.of("field", "topicIds", "message", "choose at most 100 topics"));
        }
        if ("TIME".equals(in.sizeBy())) {
            if (in.minutes() == null || in.minutes() < 1 || in.minutes() > timing.maxMinutes()) {
                errors.add(Map.of("field", "minutes", "message", "choose between 1 and " + timing.maxMinutes() + " minutes"));
            }
        } else if ("COUNT".equals(in.sizeBy())) {
            if (in.questionCount() == null || in.questionCount() < 1 || in.questionCount() > 500) {
                errors.add(Map.of("field", "questionCount", "message", "choose at least 1 question"));
            }
        } else {
            errors.add(Map.of("field", "sizeBy", "message", "must be COUNT or TIME"));
        }
        if (!errors.isEmpty()) {
            throw Requests.invalid(errors);
        }
    }

    private static Size size(StartInput in, ModeSettings mode) {
        if ("TIME".equals(in.sizeBy())) {
            if ("MOCK".equals(in.mode()) && mode.timeLimitSeconds() != null && in.minutes() * 60 > mode.timeLimitSeconds()) {
                throw Requests.invalid("minutes", "a mock exam on this exam can last at most "
                        + mode.timeLimitSeconds() / 60 + " minutes");
            }
            return Size.time(in.minutes() * 60);
        }
        if (in.questionCount() > mode.maxCount()) {
            throw Requests.invalid("questionCount", "choose at most " + mode.maxCount() + " questions");
        }
        return Size.count(in.questionCount());
    }

    /**
     * How long a mock exam lasts: the minutes chosen, or for a question count the questions' estimated solving
     * times rounded up to whole minutes. Never longer than the exam's mock time limit.
     */
    static long mockSeconds(Size size, Picked picked, ModeSettings mode) {
        long seconds = size.byTime() ? size.seconds() : Math.max(60, (picked.estimatedSeconds() + 59) / 60 * 60L);
        return mode.timeLimitSeconds() == null ? seconds : Math.min(seconds, mode.timeLimitSeconds());
    }

    // --- answer and submit --------------------------------------------------------------------------------------------

    private enum Outcome { SAVED, FINISHED, EXPIRED }

    public QuestionView answer(UUID studentId, UUID attemptId, int position, AnswerInput in) {
        Outcome outcome = tx.execute(s -> {
            AttemptRow row = attempts.lock(attemptId).filter(a -> a.studentId().equals(studentId))
                    .orElseThrow(AttemptService::notFound);
            if (row.finished()) {
                return Outcome.FINISHED;
            }
            OffsetDateTime now = now();
            if (pastEnd(row, now)) {
                finish(row, "EXPIRED", now);
                return Outcome.EXPIRED;
            }
            QuestionRow q = attempts.questions(attemptId).stream().filter(x -> x.position() == position).findFirst()
                    .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Question not found."));
            AttemptConfig config = config(row);
            boolean flagged = in.flagged() != null ? in.flagged() : q.flagged();
            int spent = (int) Math.min(Integer.MAX_VALUE, (long) q.timeSpentMs()
                    + Math.max(0, Math.min(3_600_000, in.timeSpentMs() == null ? 0 : in.timeSpentMs())));
            if (in.selected() == null) {
                attempts.saveResponse(attemptId, position, q.selected(), flagged, q.locked(), spent, q.answeredAt(),
                        q.isCorrect(), q.awarded());
            } else {
                List<String> selected = toStored(q, in.selected());
                if (config.immediate()) {
                    if (q.locked()) {
                        throw new ApiException(ProblemCode.CONFLICT, "You've already answered this question.");
                    }
                    if (selected.isEmpty()) {
                        throw Requests.invalid("selected", "choose an answer");
                    }
                    Scoring.Mark mark = Scoring.mark(Set.copyOf(q.correctLabels()), Set.copyOf(selected), q.marks(),
                            config.negativeMark());
                    attempts.saveResponse(attemptId, position, selected, flagged, true, spent, now, mark.correct(),
                            mark.awarded());
                    if (ADAPTIVE.equals(row.mode())) {
                        giveNextAdaptiveQuestion(row, now);
                    }
                } else {
                    attempts.saveResponse(attemptId, position, selected, flagged, false, spent,
                            selected.isEmpty() ? null : now, null, null);
                }
            }
            attempts.touch(attemptId, now);
            return Outcome.SAVED;
        });
        if (outcome == Outcome.FINISHED) {
            throw new ApiException(ProblemCode.ATTEMPT_FINISHED, "This attempt is already finished.");
        }
        if (outcome == Outcome.EXPIRED) {
            throw new ApiException(ProblemCode.ATTEMPT_EXPIRED,
                    "Time is up. Your answers given in time have been marked.");
        }
        AttemptRow row = attempts.find(attemptId).orElseThrow();
        QuestionRow q = attempts.questions(attemptId).stream().filter(x -> x.position() == position).findFirst()
                .orElseThrow();
        Delivered content = catalog.content(List.of(q.versionId())).get(q.versionId());
        return questionView(row, q, content);
    }

    /** Finishes the attempt and returns it with its result. Submitting a finished attempt again just returns it. */
    public AttemptView submit(UUID studentId, UUID attemptId) {
        tx.executeWithoutResult(s -> {
            AttemptRow row = attempts.lock(attemptId).filter(a -> a.studentId().equals(studentId))
                    .orElseThrow(AttemptService::notFound);
            if (!row.finished()) {
                OffsetDateTime now = now();
                finish(row, pastEnd(row, now) ? "EXPIRED" : "SUBMITTED", now);
            }
        });
        return view(studentId, attemptId);
    }

    /** Marks every question and records the result (SC-1 … SC-6). The caller holds the attempt's lock. */
    private void finish(AttemptRow row, String reason, OffsetDateTime now) {
        AttemptConfig config = config(row);
        List<BigDecimal> awarded = new ArrayList<>();
        BigDecimal max = BigDecimal.ZERO;
        int correct = 0;
        int incorrect = 0;
        int unanswered = 0;
        for (QuestionRow q : attempts.questions(row.id())) {
            max = max.add(q.marks());
            Boolean isCorrect;
            BigDecimal points;
            if (q.locked()) {
                isCorrect = q.isCorrect();
                points = q.awarded() == null ? BigDecimal.ZERO : q.awarded();
            } else {
                Scoring.Mark mark = Scoring.mark(Set.copyOf(q.correctLabels()), Set.copyOf(q.selected()), q.marks(),
                        config.negativeMark());
                isCorrect = mark.correct();
                points = mark.awarded();
                attempts.saveResponse(row.id(), q.position(), q.selected(), q.flagged(), q.locked(), q.timeSpentMs(),
                        q.answeredAt(), isCorrect, points);
            }
            awarded.add(points);
            if (isCorrect == null) {
                unanswered++;
            } else if (isCorrect) {
                correct++;
            } else {
                incorrect++;
            }
        }
        OffsetDateTime finishedAt = "EXPIRED".equals(reason) && row.expiresAt() != null && row.expiresAt().isBefore(now)
                ? row.expiresAt() : now;
        attempts.finish(row.id(), reason, finishedAt, Scoring.total(awarded), max, correct, incorrect, unanswered);
    }

    // --- timing (ADR-010) ---------------------------------------------------------------------------------------------

    private boolean pastEnd(AttemptRow row, OffsetDateTime now) {
        return row.expiresAt() != null && now.isAfter(row.expiresAt().plus(timing.grace()));
    }

    private String dueReason(AttemptRow row, OffsetDateTime now) {
        if (row.finished()) {
            return null;
        }
        if (pastEnd(row, now)) {
            return "EXPIRED";
        }
        if (row.expiresAt() == null && row.lastActivityAt().isBefore(now.minus(timing.abandonAfter()))) {
            return "ABANDONED";
        }
        return null;
    }

    /** Finishes the attempt if its time is up (or it was abandoned). Safe to call at any time. */
    public void finishIfDue(UUID attemptId) {
        tx.executeWithoutResult(s -> attempts.lock(attemptId).ifPresent(row -> {
            OffsetDateTime now = now();
            String reason = dueReason(row, now);
            if (reason != null) {
                finish(row, reason, now);
            }
        }));
    }

    /** One pass of the sweep: finishes attempts whose time is up or that were abandoned. Returns how many. */
    public int finishDue() {
        OffsetDateTime now = now();
        List<UUID> due = attempts.due(now.minus(timing.grace()), now.minus(timing.abandonAfter()), 200);
        for (UUID id : due) {
            try {
                finishIfDue(id);
            } catch (RuntimeException e) {
                log.warn("Could not finish attempt {}: {}", id, e.getMessage());
            }
        }
        return due.size();
    }

    // --- reads --------------------------------------------------------------------------------------------------------

    public AttemptView view(UUID studentId, UUID attemptId) {
        AttemptRow row = attempts.find(attemptId).filter(a -> a.studentId().equals(studentId))
                .orElseThrow(AttemptService::notFound);
        if (dueReason(row, now()) != null) {
            finishIfDue(attemptId);
            row = attempts.find(attemptId).orElseThrow();
        }
        List<QuestionRow> questions = attempts.questions(attemptId);
        Map<UUID, Delivered> content = catalog.content(questions.stream().map(QuestionRow::versionId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        AttemptRow attempt = row;
        List<QuestionView> views = questions.stream()
                .map(q -> questionView(attempt, q, content.get(q.versionId()))).toList();
        Map<UUID, String> topicTitles = new LinkedHashMap<>();
        questions.forEach(q -> topicTitles.putIfAbsent(q.topicId(), q.topicTitle()));
        List<TopicRef> topics = (row.topicIds().isEmpty() ? new ArrayList<>(topicTitles.keySet()) : row.topicIds())
                .stream().map(id -> new TopicRef(id, topicTitles.getOrDefault(id, ""))).toList();
        AttemptConfig config = config(row);
        int estimated = questions.stream().mapToInt(QuestionRow::estSeconds).sum();
        return new AttemptView(row.id(), row.examId(), row.examTitle(), row.examSlug(), row.mode(), row.status(),
                row.finishReason(), config.feedback(), row.generation(), row.requestedCount(),
                row.requestedSeconds() == null ? null : row.requestedSeconds() / 60, estimated, row.startedAt(),
                row.expiresAt(), row.finishedAt(), now(), topics, views,
                row.finished() ? result(row, questions, config) : null,
                Math.max(questions.size(), row.plannedCount() == null ? 0 : row.plannedCount()));
    }

    public List<AttemptSummary> history(UUID studentId, UUID examId) {
        return attempts.history(studentId, examId, HISTORY_LIMIT).stream().map(a -> new AttemptSummary(a.id(),
                a.examId(), a.examTitle(), a.examSlug(), a.mode(), a.status(), a.finishReason(), a.startedAt(),
                a.expiresAt(), a.finishedAt(), a.questionCount(), a.score(), a.maxScore(),
                a.finished() ? Scoring.percent(a.score(), a.maxScore()) : null, a.correctCount(), a.incorrectCount(),
                a.unansweredCount())).toList();
    }

    private QuestionView questionView(AttemptRow row, QuestionRow q, Delivered content) {
        Map<String, DeliveredOption> byLabel = content == null ? Map.of()
                : content.options().stream().collect(Collectors.toMap(DeliveredOption::label, Function.identity()));
        List<OptionView> options = new ArrayList<>();
        for (int i = 0; i < q.optionOrder().size(); i++) {
            DeliveredOption o = byLabel.get(q.optionOrder().get(i));
            options.add(new OptionView(display(i), o == null ? "" : o.bodyHtml(), o == null ? null : o.mediaUrl(),
                    o == null ? null : o.mediaAlt()));
        }
        boolean reveal = row.finished() || q.locked();
        Review review = !reveal ? null : new Review(q.isCorrect(), toDisplay(q, q.correctLabels()),
                content == null ? null : content.explanationHtml(), content == null ? List.of() : content.keyPoints(),
                content == null || content.definitions() == null ? List.of() : content.definitions(),
                q.awarded() == null ? BigDecimal.ZERO.setScale(2) : q.awarded(), q.marks());
        return new QuestionView(q.position(), q.topicId(), q.topicTitle(), q.difficulty(), q.answerType(), q.estSeconds(),
                content == null ? "" : content.stemHtml(), content == null ? null : content.stemMediaUrl(),
                content == null ? null : content.stemMediaAlt(), options, toDisplay(q, q.selected()), q.flagged(),
                !q.selected().isEmpty(), review, q.level());
    }

    private Result result(AttemptRow row, List<QuestionRow> questions, AttemptConfig config) {
        LearningProfile profile = progress.profile(row.studentId(), row.examId());
        Map<UUID, int[]> counts = new LinkedHashMap<>(); // total, correct, incorrect, unanswered
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (QuestionRow q : questions) {
            int[] c = counts.computeIfAbsent(q.topicId(), k -> new int[4]);
            titles.putIfAbsent(q.topicId(), q.topicTitle());
            c[0]++;
            if (q.isCorrect() == null) {
                c[3]++;
            } else if (q.isCorrect()) {
                c[1]++;
            } else {
                c[2]++;
            }
        }
        List<TopicResult> byTopic = counts.entrySet().stream().map(e -> new TopicResult(e.getKey(),
                titles.get(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3],
                Math.round(100f * e.getValue()[1] / e.getValue()[0]), profile.level(e.getKey()),
                TopicLevels.label(profile.level(e.getKey())))).toList();
        int percent = Scoring.percent(row.score(), row.maxScore());
        OffsetDateTime end = row.finishedAt();
        long duration = Math.max(0, Duration.between(row.startedAt(), end).toSeconds());
        return new Result(row.score(), row.maxScore(), percent, row.correctCount(), row.incorrectCount(),
                row.unansweredCount(), config.passMarkPct(),
                config.passMarkPct() == null ? null : percent >= config.passMarkPct(), duration, byTopic);
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private AttemptConfig config(AttemptRow row) {
        return json.readValue(row.configJson(), AttemptConfig.class);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String display(int index) {
        return String.valueOf((char) ('A' + index));
    }

    /** Display letters chosen by the student → the stored labels they stand for. */
    private static List<String> toStored(QuestionRow q, List<String> displayLabels) {
        Set<String> seen = new HashSet<>();
        List<String> stored = new ArrayList<>();
        for (String label : displayLabels) {
            String l = label == null ? "" : label.strip().toUpperCase(java.util.Locale.ROOT);
            int index = l.length() == 1 ? l.charAt(0) - 'A' : -1;
            if (index < 0 || index >= q.optionOrder().size()) {
                throw Requests.invalid("selected", "“" + label + "” isn't one of the options");
            }
            if (seen.add(l)) {
                stored.add(q.optionOrder().get(index));
            }
        }
        if ("SINGLE".equals(q.answerType()) && stored.size() > 1) {
            throw Requests.invalid("selected", "this question has one answer");
        }
        return stored.stream().sorted().toList();
    }

    private static List<String> toDisplay(QuestionRow q, List<String> stored) {
        List<String> display = new ArrayList<>();
        for (int i = 0; i < q.optionOrder().size(); i++) {
            if (stored.contains(q.optionOrder().get(i))) {
                display.add(display(i));
            }
        }
        return display;
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "Attempt not found.");
    }
}
