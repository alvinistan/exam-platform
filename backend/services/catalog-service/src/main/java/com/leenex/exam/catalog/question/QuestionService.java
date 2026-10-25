package com.leenex.exam.catalog.question;

import com.leenex.exam.catalog.exam.PublishReadiness;
import com.leenex.exam.catalog.media.MediaService;
import com.leenex.exam.catalog.question.QuestionContent.Definition;
import com.leenex.exam.catalog.question.QuestionContent.Option;
import com.leenex.exam.catalog.question.QuestionRepository.HistoryRow;
import com.leenex.exam.catalog.question.QuestionRepository.QuestionRow;
import com.leenex.exam.catalog.question.QuestionRepository.VersionRow;
import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question authoring (FR-QB-01 … FR-QB-12). A question's current version is edited in place until it is published;
 * publishing freezes it, and the next edit starts a new version (FR-QB-05). Students are only ever served the newest
 * frozen version of a Published question.
 */
@Service
public class QuestionService {

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9 -]{0,40}$");
    private static final String LABELS = "ABCDE";

    private final QuestionRepository questions;
    private final RichText richText;
    private final MediaService media;
    private final PublishReadiness readiness;
    private final AuditLog audit;
    private final JdbcClient jdbc;

    public QuestionService(QuestionRepository questions, RichText richText, MediaService media,
                           PublishReadiness readiness, AuditLog audit, JdbcClient jdbc) {
        this.questions = questions;
        this.richText = richText;
        this.media = media;
        this.readiness = readiness;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // --- input ------------------------------------------------------------------------------------------------------

    public record OptionInput(String bodyHtml, String mediaKey, String mediaAlt, boolean correct, UUID misconceptionId) {
    }

    /** Question fields as sent by the editor or an import row; shape already checked by the controller. */
    public record Input(UUID topicId, String answerType, String stemHtml, String stemMediaKey, String stemMediaAlt,
                        List<OptionInput> options, String explanationHtml, List<Definition> definitions,
                        List<String> keyPoints, String difficulty, int estSeconds, BigDecimal marks, List<String> tags) {
    }

    // --- views ------------------------------------------------------------------------------------------------------

    public record TopicPath(UUID subjectId, String subjectTitle, UUID moduleId, String moduleTitle, String topicTitle,
                            boolean active) {
    }

    public record OptionView(String label, String bodyHtml, String mediaKey, String mediaUrl, String mediaAlt,
                             boolean isCorrect, UUID misconceptionId) {
    }

    public record Validation(boolean valid, List<String> problems) {
    }

    public record History(int versionNo, boolean published, OffsetDateTime publishedAt, UUID createdBy,
                          OffsetDateTime createdAt) {
    }

    public record QuestionDetail(UUID id, Bank bank, String status, UUID topicId, TopicPath topic, String answerType,
                                 String stemHtml, String stemMediaKey, String stemMediaUrl, String stemMediaAlt,
                                 List<OptionView> options, String explanationHtml, List<Definition> definitions,
                                 List<String> keyPoints, String difficulty, int estSeconds, BigDecimal marks,
                                 List<String> tags, int versionNo, Integer publishedVersionNo, boolean hasDraftChanges,
                                 int version, Validation validation, OffsetDateTime createdAt, UUID createdBy,
                                 OffsetDateTime updatedAt, UUID updatedBy, List<History> history) {
    }

    // --- reads ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public QuestionDetail get(UUID id) {
        Bank bank = questions.bankOf(id).orElseThrow(QuestionService::notFound);
        return detail(bank, questions.find(bank, id).orElseThrow(QuestionService::notFound));
    }

    // --- writes -----------------------------------------------------------------------------------------------------

    @Transactional
    public QuestionDetail create(UUID actor, Bank bank, Input input) {
        QuestionContent content = prepare(input, null);
        UUID id = UuidV7.generate();
        questions.insertQuestion(bank, id, content.topicId(), "DRAFT", actor);
        questions.insertVersion(bank, UuidV7.generate(), id, 1, content, richText.toText(content.stemHtml()), actor);
        questions.replaceTags(bank, id, content.tags());
        audit.record(actor, "QUESTION_CREATED", "QUESTION", id, Map.of("bank", bank.name()));
        return get(id);
    }

    /** Creates several drafts in one transaction (confirmed imports). Content is already prepared. */
    @Transactional
    public List<UUID> createAll(UUID actor, Bank bank, List<Input> inputs) {
        List<UUID> ids = new ArrayList<>();
        for (Input input : inputs) {
            QuestionContent content = prepare(input, null);
            UUID id = UuidV7.generate();
            questions.insertQuestion(bank, id, content.topicId(), "DRAFT", actor);
            questions.insertVersion(bank, UuidV7.generate(), id, 1, content, richText.toText(content.stemHtml()), actor);
            questions.replaceTags(bank, id, content.tags());
            ids.add(id);
        }
        return ids;
    }

    @Transactional
    public QuestionDetail save(UUID actor, UUID id, int expectedVersion, Bank requestedBank, Input input) {
        Bank bank = questions.bankOf(id).orElseThrow(QuestionService::notFound);
        if (requestedBank != null && requestedBank != bank) {
            throw Requests.invalid("bank", "use the bulk “Change bank” action to move a question between banks");
        }
        QuestionRow row = questions.lock(bank, id).orElseThrow(QuestionService::notFound);
        if (row.version() != expectedVersion) {
            throw Requests.staleVersion();
        }
        QuestionContent content = prepare(input, row.topicId());
        VersionRow current = questions.version(bank, id, row.currentVersionNo()).orElseThrow();
        String stemText = richText.toText(content.stemHtml());
        int versionNo = row.currentVersionNo();
        String status = row.status();
        if (current.frozen()) {
            // Published content never changes: the edit becomes a new draft version (FR-QB-05).
            versionNo = row.currentVersionNo() + 1;
            questions.insertVersion(bank, UuidV7.generate(), id, versionNo, content, stemText, actor);
        } else {
            questions.replaceVersion(bank, current.id(), content, stemText);
        }
        if ("REVIEWED".equals(status)) {
            status = "DRAFT"; // changed after review: it needs reviewing again
        }
        questions.updateQuestion(bank, id, content.topicId(), status, versionNo, actor);
        questions.replaceTags(bank, id, content.tags());
        if (!Objects.equals(row.topicId(), content.topicId()) && "PUBLISHED".equals(row.status())) {
            readiness.requireStillPublishable(publishedExamsUsingTopics(List.of(row.topicId())));
        }
        audit.record(actor, "QUESTION_SAVED", "QUESTION", id, Map.of("versionNo", versionNo));
        return get(id);
    }

    /**
     * The workflow (FR-QB-03): Draft → (Reviewed) → Published → Archived, with the moves back an editor needs.
     * Review is an optional step for teams; the web app calls a published question "Ready".
     */
    @Transactional
    public QuestionDetail transition(UUID actor, UUID id, String to) {
        Bank bank = questions.bankOf(id).orElseThrow(QuestionService::notFound);
        QuestionRow row = questions.lock(bank, id).orElseThrow(QuestionService::notFound);
        VersionRow current = questions.version(bank, id, row.currentVersionNo()).orElseThrow();
        String from = row.status();
        boolean allowed = switch (to) {
            case "REVIEWED" -> "DRAFT".equals(from);
            case "DRAFT" -> "REVIEWED".equals(from) || "ARCHIVED".equals(from);
            // Review is optional: a complete draft can be marked ready (published) directly.
            case "PUBLISHED" -> "DRAFT".equals(from) || "REVIEWED".equals(from)
                    || ("PUBLISHED".equals(from) && !current.frozen());
            case "ARCHIVED" -> !"ARCHIVED".equals(from);
            default -> false;
        };
        if (!allowed) {
            throw new ApiException(ProblemCode.CONFLICT, "A " + from.toLowerCase() + " question can't move to "
                    + to.toLowerCase() + ".");
        }
        if ("REVIEWED".equals(to) || "PUBLISHED".equals(to)) {
            List<String> problems = QuestionChecks.problems(current.content(), topicActive(row.topicId()));
            if (!problems.isEmpty()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Fix these before the question can move on.",
                        Map.of("problems", problems));
            }
        }
        if ("PUBLISHED".equals(to)) {
            questions.freeze(bank, current.id());
        }
        questions.updateQuestion(bank, id, row.topicId(), to, row.currentVersionNo(), actor);
        if ("PUBLISHED".equals(from) && !"PUBLISHED".equals(to)) {
            readiness.requireStillPublishable(publishedExamsUsingTopics(List.of(row.topicId())));
        }
        audit.record(actor, "QUESTION_" + to, "QUESTION", id,
                Map.of("from", from, "versionNo", row.currentVersionNo()));
        return get(id);
    }

    /** Throws away unpublished edits to a published question, going back to its published version. */
    @Transactional
    public QuestionDetail discardDraft(UUID actor, UUID id) {
        Bank bank = questions.bankOf(id).orElseThrow(QuestionService::notFound);
        QuestionRow row = questions.lock(bank, id).orElseThrow(QuestionService::notFound);
        VersionRow current = questions.version(bank, id, row.currentVersionNo()).orElseThrow();
        Optional<VersionRow> published = questions.latestFrozen(bank, id);
        if (current.frozen() || published.isEmpty()) {
            throw new ApiException(ProblemCode.CONFLICT, "There are no unpublished changes to discard.");
        }
        questions.deleteVersion(bank, current.id());
        questions.updateQuestion(bank, id, row.topicId(), row.status(), published.get().versionNo(), actor);
        audit.record(actor, "QUESTION_DRAFT_DISCARDED", "QUESTION", id, Map.of("versionNo", row.currentVersionNo()));
        return get(id);
    }

    // --- bulk (FR-QB-09) ----------------------------------------------------------------------------------------------

    public record BulkResult(int updated, List<UUID> createdIds) {
    }

    @Transactional
    public BulkResult bulk(UUID actor, List<UUID> ids, String action, String status, UUID topicId, Bank targetBank) {
        if ("MOVE_TOPIC".equals(action) && !"ACTIVE".equals(jdbc.sql("SELECT status FROM catalog.topic WHERE id = :id")
                .param("id", topicId).query(String.class).optional().orElse(""))) {
            throw Requests.invalid("topicId", "choose an active topic");
        }
        Set<UUID> topicsTouched = new LinkedHashSet<>();
        List<Map<String, String>> problems = new ArrayList<>();
        List<UUID> created = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            Optional<Bank> bank = questions.bankOf(id);
            if (bank.isEmpty()) {
                problems.add(Map.of("field", id.toString(), "message", "question not found"));
                continue;
            }
            try {
                switch (action) {
                    case "SET_STATUS" -> {
                        QuestionRow row = questions.find(bank.get(), id).orElseThrow();
                        if (!row.status().equals(status)) {
                            transition(actor, id, status);
                        }
                        topicsTouched.add(row.topicId());
                    }
                    case "MOVE_TOPIC" -> {
                        QuestionRow row = questions.lock(bank.get(), id).orElseThrow();
                        topicsTouched.add(row.topicId());
                        questions.updateQuestion(bank.get(), id, topicId, row.status(), row.currentVersionNo(), actor);
                    }
                    case "CHANGE_BANK" -> {
                        if (bank.get() != targetBank) {
                            QuestionRow row = questions.find(bank.get(), id).orElseThrow();
                            topicsTouched.add(row.topicId());
                            created.add(moveToBank(actor, bank.get(), row, targetBank));
                        }
                    }
                    default -> throw new IllegalArgumentException(action);
                }
            } catch (ApiException e) {
                Object detail = e.properties().get("problems");
                problems.add(Map.of("field", id.toString(), "message",
                        detail instanceof List<?> list && !list.isEmpty() ? e.getMessage() + " " + list.getFirst() : e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            // Nothing is applied unless every question can take the action.
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some questions can't take this action. Nothing was changed.",
                    Map.of("errors", problems));
        }
        readiness.requireStillPublishable(publishedExamsUsingTopics(topicsTouched));
        audit.record(actor, "QUESTIONS_BULK_" + action, "QUESTION", null, Map.of("count", ids.size(),
                "status", String.valueOf(status), "topicId", String.valueOf(topicId), "bank", String.valueOf(targetBank)));
        return new BulkResult(ids.size(), created);
    }

    /**
     * Copies a question into the other bank and archives the original (ADR-009). The copy keeps the status and the
     * published content; unpublished edits must be published or discarded first, so nothing half-done moves.
     */
    private UUID moveToBank(UUID actor, Bank from, QuestionRow row, Bank to) {
        VersionRow current = questions.version(from, row.id(), row.currentVersionNo()).orElseThrow();
        Optional<VersionRow> published = questions.latestFrozen(from, row.id());
        if (!current.frozen() && published.isPresent()) {
            throw new ApiException(ProblemCode.CONFLICT, "Publish or discard its unpublished changes first.");
        }
        UUID copyId = UuidV7.generate();
        UUID versionId = UuidV7.generate();
        questions.insertQuestion(to, copyId, row.topicId(), "ARCHIVED".equals(row.status()) ? "DRAFT" : row.status(), actor);
        questions.insertVersion(to, versionId, copyId, 1, current.content(), richText.toText(current.content().stemHtml()), actor);
        if (current.frozen()) {
            questions.freeze(to, versionId);
        }
        questions.replaceTags(to, copyId, questions.tags(from, row.id()));
        if (!"ARCHIVED".equals(row.status())) {
            questions.updateQuestion(from, row.id(), row.topicId(), "ARCHIVED", row.currentVersionNo(), actor);
        }
        audit.record(actor, "QUESTION_MOVED_BANK", "QUESTION", row.id(), Map.of("to", to.name(), "copyId", copyId.toString()));
        return copyId;
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    /** Sanitises and checks the shape of an edit. Completeness (FR-QB-04) is checked separately. */
    private QuestionContent prepare(Input in, UUID currentTopicId) {
        List<Map<String, String>> errors = new ArrayList<>();
        Optional<String> topicStatus = jdbc.sql("SELECT status FROM catalog.topic WHERE id = :id")
                .param("id", in.topicId()).query(String.class).optional();
        if (topicStatus.isEmpty()) {
            errors.add(error("topicId", "topic does not exist"));
        } else if (!"ACTIVE".equals(topicStatus.get()) && !in.topicId().equals(currentTopicId)) {
            errors.add(error("topicId", "topic is archived"));
        }
        String stem = richText.sanitize(in.stemHtml());
        String stemMedia = blankToNull(in.stemMediaKey());
        if (stem.isEmpty() && stemMedia == null) {
            errors.add(error("stemHtml", "write the question or add an image"));
        }
        checkMedia(stemMedia, in.stemMediaAlt(), "stemMediaKey", errors);
        if (in.options().size() > 5) {
            errors.add(error("options", "at most 5 options"));
        }
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < in.options().size() && i < 5; i++) {
            OptionInput o = in.options().get(i);
            String field = "options[" + i + "]";
            String mediaKey = blankToNull(o.mediaKey());
            checkMedia(mediaKey, o.mediaAlt(), field + ".mediaKey", errors);
            if (o.misconceptionId() != null) {
                if (o.correct()) {
                    errors.add(error(field + ".misconceptionId", "only wrong options can point to a misconception"));
                } else if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.misconception WHERE id = :id)")
                        .param("id", o.misconceptionId()).query(Boolean.class).single()) {
                    errors.add(error(field + ".misconceptionId", "misconception does not exist"));
                }
            }
            options.add(new Option(String.valueOf(LABELS.charAt(i)), richText.sanitize(o.bodyHtml()), mediaKey,
                    mediaKey == null || o.mediaAlt() == null ? null : o.mediaAlt().strip(), o.correct(),
                    o.misconceptionId()));
        }
        List<String> tags = new ArrayList<>();
        for (String raw : in.tags() == null ? List.<String>of() : in.tags()) {
            String tag = raw.strip().toLowerCase().replaceAll("\\s+", " ");
            if (tag.isEmpty() || tags.contains(tag)) {
                continue;
            }
            if (!TAG.matcher(tag).matches()) {
                errors.add(error("tags", "\"" + raw + "\": use letters, digits, spaces and hyphens, up to 41 characters"));
            }
            tags.add(tag);
        }
        if (tags.size() > 20) {
            errors.add(error("tags", "at most 20 tags"));
        }
        if (!errors.isEmpty()) {
            throw Requests.invalid(errors);
        }
        List<String> keyPoints = in.keyPoints() == null ? List.of()
                : in.keyPoints().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        List<Definition> definitions = in.definitions() == null ? List.of()
                : in.definitions().stream().filter(d -> d.term() != null && !d.term().isBlank())
                .map(d -> new Definition(d.term().strip(), d.meaning() == null ? "" : d.meaning().strip())).toList();
        return new QuestionContent(in.topicId(), in.answerType(), stem, stemMedia,
                stemMedia == null ? null : in.stemMediaAlt().strip(), options, richText.sanitize(in.explanationHtml()),
                definitions, keyPoints, in.difficulty(), in.estSeconds(), in.marks() == null ? BigDecimal.ONE : in.marks(),
                List.copyOf(tags));
    }

    private void checkMedia(String key, String alt, String field, List<Map<String, String>> errors) {
        if (key == null) {
            return;
        }
        if (!media.exists(key)) {
            errors.add(error(field, "image not found; upload it again"));
        }
        if (alt == null || alt.isBlank()) {
            errors.add(error(field.replace("Key", "Alt"), "describe the image for screen-reader users"));
        }
    }

    private QuestionDetail detail(Bank bank, QuestionRow row) {
        VersionRow current = questions.version(bank, row.id(), row.currentVersionNo()).orElseThrow();
        Optional<VersionRow> published = questions.latestFrozen(bank, row.id());
        TopicPath path = topicPath(row.topicId());
        QuestionContent c = current.content();
        List<String> problems = QuestionChecks.problems(c, path.active());
        List<OptionView> options = c.options().stream().map(o -> new OptionView(o.label(), o.bodyHtml(), o.mediaKey(),
                media.signedUrl(o.mediaKey()), o.mediaAlt(), o.correct(), o.misconceptionId())).toList();
        List<History> history = questions.history(bank, row.id()).stream().map(QuestionService::history).toList();
        return new QuestionDetail(row.id(), bank, row.status(), row.topicId(), path, c.answerType(), c.stemHtml(),
                c.stemMediaKey(), media.signedUrl(c.stemMediaKey()), c.stemMediaAlt(), options, c.explanationHtml(),
                c.definitions(), c.keyPoints(), c.difficulty(), c.estSeconds(), c.marks(), questions.tags(bank, row.id()),
                current.versionNo(), published.map(VersionRow::versionNo).orElse(null),
                !current.frozen() && published.isPresent(), row.version(),
                new Validation(problems.isEmpty(), problems), row.createdAt(), row.createdBy(), row.updatedAt(),
                row.updatedBy(), history);
    }

    private static History history(HistoryRow h) {
        return new History(h.versionNo(), h.frozenAt() != null, h.frozenAt(), h.createdBy(), h.createdAt());
    }

    private TopicPath topicPath(UUID topicId) {
        return jdbc.sql("""
                SELECT s.id AS sid, s.title AS stitle, m.id AS mid, m.title AS mtitle, t.title AS ttitle,
                       (t.status = 'ACTIVE' AND m.status = 'ACTIVE' AND s.status = 'ACTIVE') AS active
                FROM catalog.topic t JOIN catalog.module m ON m.id = t.module_id JOIN catalog.subject s ON s.id = m.subject_id
                WHERE t.id = :id
                """)
                .param("id", topicId)
                .query((rs, n) -> new TopicPath(rs.getObject("sid", UUID.class), rs.getString("stitle"),
                        rs.getObject("mid", UUID.class), rs.getString("mtitle"), rs.getString("ttitle"),
                        rs.getBoolean("active")))
                .single();
    }

    private boolean topicActive(UUID topicId) {
        return topicPath(topicId).active();
    }

    List<UUID> publishedExamsUsingTopics(Collection<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT e.id FROM catalog.topic t
                JOIN catalog.module m ON m.id = t.module_id
                JOIN catalog.exam_subject es ON es.subject_id = m.subject_id
                JOIN catalog.exam e ON e.id = es.exam_id AND e.status = 'PUBLISHED'
                WHERE t.id IN (:topics)
                """).param("topics", topicIds).query(UUID.class).list();
    }

    private static Map<String, String> error(String field, String message) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("message", message);
        return e;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "Question not found.");
    }
}
