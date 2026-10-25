package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.client.CatalogClient.Definition;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What students see of an attempt. Option labels here are display letters (A, B, C … in the order shown), never the
 * catalogue's own labels, and correct answers appear only once they may be disclosed.
 */
public final class AttemptViews {

    private AttemptViews() {
    }

    public record TopicRef(UUID id, String title) {
    }

    public record OptionView(String label, String bodyHtml, String mediaUrl, String mediaAlt) {
    }

    /** Shown after the answer is checked (immediate feedback) or after the attempt is finished. */
    public record Review(Boolean correct, List<String> correctLabels, String explanationHtml, List<String> keyPoints,
                         List<Definition> definitions, BigDecimal awarded, BigDecimal marks) {
    }

    public record QuestionView(int position, UUID topicId, String topicTitle, String difficulty, String answerType,
                               int estSeconds, String stemHtml, String stemMediaUrl, String stemMediaAlt,
                               List<OptionView> options, List<String> selected, boolean flagged, boolean answered,
                               Review review) {
    }

    public record TopicResult(UUID topicId, String title, int total, int correct, int incorrect, int unanswered,
                              int percent) {
    }

    public record Result(BigDecimal score, BigDecimal maxScore, int percent, int correct, int incorrect,
                         int unanswered, Integer passMarkPct, Boolean passed, long durationSeconds,
                         List<TopicResult> byTopic) {
    }

    /**
     * @param feedback       IMMEDIATE: each answer is checked when given; AFTER_SUBMIT: answers and explanations
     *                       appear only after finishing
     * @param requestedCount what the student asked for (COUNT sessions); {@code questions} may be fewer when the
     *                       pool is smaller
     * @param serverNow      the server's clock, so the client can render the countdown without trusting its own
     */
    public record AttemptView(UUID id, UUID examId, String examTitle, String examSlug, String mode, String status,
                              String finishReason, String feedback, String sizeBy, Integer requestedCount,
                              Integer requestedMinutes, int estimatedSeconds, OffsetDateTime startedAt,
                              OffsetDateTime expiresAt, OffsetDateTime finishedAt, OffsetDateTime serverNow,
                              List<TopicRef> topics, List<QuestionView> questions, Result result) {
    }

    public record AttemptSummary(UUID id, UUID examId, String examTitle, String examSlug, String mode, String status,
                                 String finishReason, OffsetDateTime startedAt, OffsetDateTime expiresAt,
                                 OffsetDateTime finishedAt, int questionCount, BigDecimal score, BigDecimal maxScore,
                                 Integer percent, Integer correct, Integer incorrect, Integer unanswered) {
    }
}
