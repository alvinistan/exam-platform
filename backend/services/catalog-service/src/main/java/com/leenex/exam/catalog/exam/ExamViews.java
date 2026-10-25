package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.exam.PublishReadiness.Readiness;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shapes for exams (contracts/openapi/catalog-service.yaml). None of them carries question content. */
public final class ExamViews {

    private ExamViews() {
    }

    /** Catalogue card. {@code coverUrl} stays null until cover uploads arrive with media handling. */
    public record ExamSummary(UUID id, String title, String slug, UUID categoryId, String shortDescription,
                              String coverUrl, boolean featured, Integer displayOrder, List<String> modes) {
    }

    public record CategoryRef(UUID id, String name, String slug) {
    }

    public record Seo(String title, String description) {
    }

    public record TopicNode(UUID id, String title, int displayOrder) {
    }

    public record ModuleNode(UUID id, String title, List<TopicNode> topics) {
    }

    public record SubjectNode(UUID id, String title, List<ModuleNode> modules) {
    }

    /** The part of a mode's settings students may see: counts, timing and marking. */
    public record PublicModeSettings(String mode, boolean enabled, int defaultCount, int minCount, int maxCount,
                                     Integer timeLimitSeconds, Integer perQuestionSeconds, String feedbackTiming,
                                     BigDecimal negativeMark, Integer passMarkPct, boolean allowTopicChoice,
                                     Integer maxRetakes) {
        static PublicModeSettings of(ModeSettings s) {
            return new PublicModeSettings(s.mode(), s.enabled(), s.defaultCount(), s.minCount(), s.maxCount(),
                    s.timeLimitSeconds(), s.perQuestionSeconds(), s.feedbackTiming(), s.negativeMark(),
                    s.passMarkPct(), s.allowTopicChoice(), s.maxRetakes());
        }
    }

    public record ExamDetail(UUID id, String title, String slug, UUID categoryId, CategoryRef category,
                             String shortDescription, String coverUrl, boolean featured, Integer displayOrder,
                             List<String> modes, String instructions, Seo seo, List<SubjectNode> subjects,
                             List<PublicModeSettings> modeSettings) {
    }

    public record AdminExam(UUID id, String title, String slug, UUID categoryId, String shortDescription,
                            String coverUrl, boolean featured, Integer displayOrder, List<String> modes, String status,
                            OffsetDateTime publishedAt, OffsetDateTime updatedAt, int version,
                            int publishedQuestionCount, int trialQuestionCount, int topicCount,
                            Readiness publishReadiness) {
    }

    public record AdminExamDetail(UUID id, String title, String slug, UUID categoryId, String shortDescription,
                                  String coverUrl, boolean featured, Integer displayOrder, List<String> modes,
                                  String status, OffsetDateTime publishedAt, OffsetDateTime updatedAt, int version,
                                  int publishedQuestionCount, int trialQuestionCount, int topicCount,
                                  Readiness publishReadiness, String instructions, String seoTitle,
                                  String seoDescription, List<UUID> subjectIds, List<ModeSettings> modeSettings) {
    }
}
