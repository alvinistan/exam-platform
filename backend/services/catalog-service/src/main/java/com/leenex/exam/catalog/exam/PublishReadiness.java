package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.exam.ExamRepository.ContentCounts;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Decides whether an exam can be published (FR-CAT-05), and keeps published exams publishable when their content or
 * settings change.
 *
 * <p>Question counts use the Main bank: it holds an exam's full content. Trial-bank counts are reported separately for
 * information; how the free path uses them is decided with the access rules in a later phase.
 */
@Component
public class PublishReadiness {

    /** The setup steps an administrator follows; each publishing problem points at the step that fixes it. */
    public enum Step { DETAILS, STRUCTURE, QUESTIONS, MODES }

    public record Issue(Step step, String message) {
    }

    /** {@code blockers} repeats the issue messages for clients written before setup steps existed. */
    public record Readiness(boolean ready, List<String> blockers, List<Issue> issues) {
        static Readiness of(List<Issue> issues) {
            return new Readiness(issues.isEmpty(), issues.stream().map(Issue::message).toList(), List.copyOf(issues));
        }
    }

    private final ExamRepository exams;
    private final ModeRepository modes;
    private final JdbcClient jdbc;

    public PublishReadiness(ExamRepository exams, ModeRepository modes, JdbcClient jdbc) {
        this.exams = exams;
        this.modes = modes;
        this.jdbc = jdbc;
    }

    public Readiness evaluate(ExamRow exam) {
        return evaluate(exam, exams.counts(exam.id()));
    }

    public Readiness evaluate(ExamRow exam, ContentCounts counts) {
        List<Issue> issues = new ArrayList<>();
        String categoryStatus = jdbc.sql("SELECT status FROM catalog.exam_category WHERE id = :id")
                .param("id", exam.categoryId()).query(String.class).single();
        if (!"ACTIVE".equals(categoryStatus)) {
            issues.add(new Issue(Step.DETAILS, "The exam's category is archived. Choose an active category."));
        }
        List<ModeSettings> enabled = modes.enabled(exam.id());
        if (enabled.isEmpty()) {
            issues.add(new Issue(Step.MODES, "Turn on at least one mode."));
        }
        if (counts.topics() == 0) {
            issues.add(new Issue(Step.STRUCTURE, "Add a subject with at least one topic."));
        }
        for (ModeSettings mode : enabled) {
            if (counts.publishedMain() < mode.minCount()) {
                issues.add(new Issue(Step.QUESTIONS, ModeSettings.label(mode.mode()) + " needs at least " + mode.minCount()
                        + " ready questions; " + counts.publishedMain() + " ready."));
            }
        }
        if (exam.displayOrder() != null) {
            exams.publishedAtPosition(exam.displayOrder(), exam.id()).ifPresent(other -> issues.add(new Issue(Step.DETAILS,
                    "Catalogue position " + exam.displayOrder() + " is used by the published exam \"" + other.title()
                            + "\". Choose another position.")));
        }
        return Readiness.of(issues);
    }

    /**
     * Call at the end of a change, inside its transaction: if a published exam would stop being publishable, the change
     * is refused with PUBLISH_BLOCKED and rolled back.
     */
    public void requireStillPublishable(Collection<UUID> examIds) {
        for (UUID examId : examIds) {
            ExamRow exam = exams.findById(examId).orElseThrow();
            if (!exam.published()) {
                continue;
            }
            Readiness readiness = evaluate(exam);
            if (!readiness.ready()) {
                throw new ApiException(ProblemCode.PUBLISH_BLOCKED,
                        "This change would leave the published exam \"" + exam.title() + "\" unable to run. "
                                + "Archive the exam first, or fix the problems listed.",
                        Map.of("blockers", readiness.blockers(), "issues", readiness.issues()));
            }
        }
    }
}
