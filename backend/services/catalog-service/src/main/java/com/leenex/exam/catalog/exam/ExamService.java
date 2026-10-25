package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.exam.ExamRepository.ContentCounts;
import com.leenex.exam.catalog.exam.ExamRepository.ExamFields;
import com.leenex.exam.catalog.exam.ExamRepository.SearchResult;
import com.leenex.exam.catalog.exam.ExamRepository.TreeRow;
import com.leenex.exam.catalog.exam.ExamViews.AdminExam;
import com.leenex.exam.catalog.exam.ExamViews.AdminExamDetail;
import com.leenex.exam.catalog.exam.ExamViews.CategoryRef;
import com.leenex.exam.catalog.exam.ExamViews.ExamDetail;
import com.leenex.exam.catalog.exam.ExamViews.ExamSummary;
import com.leenex.exam.catalog.exam.ExamViews.ModuleNode;
import com.leenex.exam.catalog.exam.ExamViews.PublicModeSettings;
import com.leenex.exam.catalog.exam.ExamViews.Seo;
import com.leenex.exam.catalog.exam.ExamViews.SubjectNode;
import com.leenex.exam.catalog.exam.ExamViews.TopicNode;
import com.leenex.exam.catalog.exam.PublishReadiness.Readiness;
import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.catalog.support.Outbox;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.catalog.support.Requests.Page;
import com.leenex.exam.catalog.support.Requests.PageMeta;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exam definitions: create, edit, configure modes, publish and archive (FR-CAT-02 … FR-CAT-10). */
@Service
public class ExamService {

    private final ExamRepository exams;
    private final ModeRepository modes;
    private final PublishReadiness readiness;
    private final AuditLog audit;
    private final Outbox outbox;
    private final JdbcClient jdbc;

    public ExamService(ExamRepository exams, ModeRepository modes, PublishReadiness readiness, AuditLog audit,
                       Outbox outbox, JdbcClient jdbc) {
        this.exams = exams;
        this.modes = modes;
        this.readiness = readiness;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** Editable fields of an exam, already validated for shape by the controller. */
    public record ExamInput(String title, String slug, UUID categoryId, String shortDescription, String instructions,
                            String seoTitle, String seoDescription, Integer displayOrder, boolean featured,
                            List<UUID> subjectIds) {
    }

    // --- administration ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminExam> adminSearch(String q, String status, UUID categoryId, int page, int size) {
        SearchResult result = exams.adminSearch(q, status, categoryId, page, size);
        return new Page<>(result.items().stream().map(this::adminView).toList(), PageMeta.of(page, size, result.total()));
    }

    @Transactional(readOnly = true)
    public AdminExamDetail adminGet(UUID id) {
        return adminDetail(find(id));
    }

    @Transactional
    public AdminExamDetail create(UUID actorId, ExamInput input) {
        checkCategory(input.categoryId(), null);
        checkSlug(input.slug(), null);
        List<UUID> subjectIds = checkSubjects(input.subjectIds());
        UUID id = UuidV7.generate();
        exams.insert(id, fields(input), actorId);
        exams.replaceSubjects(id, subjectIds);
        audit.record(actorId, "EXAM_CREATED", "EXAM", id, Map.of("slug", input.slug()));
        return adminDetail(find(id));
    }

    @Transactional
    public AdminExamDetail update(UUID actorId, UUID id, int expectedVersion, ExamInput input) {
        ExamRow before = exams.lock(id).orElseThrow(ExamService::notFound);
        if (before.version() != expectedVersion) {
            throw Requests.staleVersion();
        }
        checkCategory(input.categoryId(), before.categoryId());
        checkSlug(input.slug(), id);
        List<UUID> subjectIds = checkSubjects(input.subjectIds());
        if (before.published() && input.displayOrder() != null) {
            exams.publishedAtPosition(input.displayOrder(), id).ifPresent(other -> {
                throw new ApiException(ProblemCode.CONFLICT, "Catalogue position " + input.displayOrder()
                        + " is used by the published exam \"" + other.title() + "\".");
            });
        }
        // A published exam keeps a catalogue position: free access counts positions (FA-1).
        Integer order = before.published() && input.displayOrder() == null ? before.displayOrder() : input.displayOrder();
        ExamInput effective = new ExamInput(input.title(), input.slug(), input.categoryId(), input.shortDescription(),
                input.instructions(), input.seoTitle(), input.seoDescription(), order, input.featured(), subjectIds);
        exams.update(id, fields(effective), actorId);
        exams.replaceSubjects(id, subjectIds);
        readiness.requireStillPublishable(List.of(id));

        Map<String, Object> summary = new LinkedHashMap<>();
        if (!before.slug().equals(input.slug())) {
            summary.put("slug", Map.of("from", before.slug(), "to", input.slug()));
        }
        if (!Objects.equals(before.displayOrder(), order)) {
            summary.put("displayOrder", Map.of("from", String.valueOf(before.displayOrder()), "to", String.valueOf(order)));
        }
        audit.record(actorId, "EXAM_UPDATED", "EXAM", id, summary);
        ExamRow after = find(id);
        if (after.published()) {
            outbox.publish("Exam", id, "ExamUpdated", eventPayload(after));
        }
        return adminDetail(after);
    }

    @Transactional
    public ModeSettings configureMode(UUID actorId, UUID examId, ModeSettings settings) {
        ExamRow exam = exams.lock(examId).orElseThrow(ExamService::notFound);
        ModeSettings before = modes.forExam(examId).stream().filter(m -> m.mode().equals(settings.mode())).findFirst()
                .orElseThrow();
        modes.save(examId, settings);
        exams.touch(examId, actorId);
        readiness.requireStillPublishable(List.of(examId));
        audit.record(actorId, "EXAM_MODE_CONFIGURED", "EXAM", examId,
                Map.of("mode", settings.mode(), "enabled", settings.enabled(), "wasEnabled", before.enabled()));
        if (exam.published()) {
            outbox.publish("Exam", examId, "ExamUpdated", eventPayload(find(examId)));
        }
        return modes.forExam(examId).stream().filter(m -> m.mode().equals(settings.mode())).findFirst().orElseThrow();
    }

    @Transactional
    public AdminExamDetail publish(UUID actorId, UUID id) {
        ExamRow exam = exams.lock(id).orElseThrow(ExamService::notFound);
        if (exam.published()) {
            throw new ApiException(ProblemCode.CONFLICT, "This exam is already published.");
        }
        Readiness check = readiness.evaluate(exam);
        if (!check.ready()) {
            throw new ApiException(ProblemCode.PUBLISH_BLOCKED, "This exam can't be published yet.",
                    Map.of("blockers", check.blockers()));
        }
        int order = exam.displayOrder() != null ? exam.displayOrder() : exams.nextDisplayOrder();
        exams.setStatus(id, "PUBLISHED", order, actorId);
        ExamRow after = find(id);
        audit.record(actorId, "EXAM_PUBLISHED", "EXAM", id, Map.of("slug", after.slug(), "displayOrder", order));
        outbox.publish("Exam", id, "ExamPublished", eventPayload(after));
        return adminDetail(after);
    }

    /** Archived exams disappear from the catalogue; their URL shows "unavailable" and past attempts stay viewable. */
    @Transactional
    public AdminExamDetail archive(UUID actorId, UUID id) {
        ExamRow exam = exams.lock(id).orElseThrow(ExamService::notFound);
        if ("ARCHIVED".equals(exam.status())) {
            throw new ApiException(ProblemCode.CONFLICT, "This exam is already archived.");
        }
        exams.setStatus(id, "ARCHIVED", exam.displayOrder(), actorId);
        ExamRow after = find(id);
        audit.record(actorId, "EXAM_ARCHIVED", "EXAM", id, Map.of("previousStatus", exam.status()));
        outbox.publish("Exam", id, "ExamArchived", eventPayload(after));
        return adminDetail(after);
    }

    // --- public catalogue -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ExamSummary> publicSearch(String q, String category, Boolean featured, int page, int size) {
        SearchResult result = exams.publicSearch(q, category, featured, page, size);
        return new Page<>(result.items().stream().map(this::summary).toList(), PageMeta.of(page, size, result.total()));
    }

    /** What a URL resolves to (FR-CAT-06, FR-CAT-10). */
    public sealed interface SlugResolution {
        record Found(ExamDetail exam) implements SlugResolution {
        }

        record Moved(String currentSlug) implements SlugResolution {
        }
    }

    @Transactional(readOnly = true)
    public SlugResolution resolveSlug(String slug) {
        Optional<ExamRow> current = exams.findBySlug(slug);
        if (current.isEmpty()) {
            return exams.formerOwnerOfSlug(slug).flatMap(exams::findById)
                    .<SlugResolution>map(e -> new SlugResolution.Moved(e.slug()))
                    .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "No exam has this address."));
        }
        ExamRow exam = current.get();
        if (!exam.published()) {
            throw new ApiException(ProblemCode.EXAM_UNAVAILABLE, "This exam isn't available at the moment.");
        }
        return new SlugResolution.Found(publicDetail(exam));
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private ExamDetail publicDetail(ExamRow e) {
        CategoryRef category = jdbc.sql("SELECT id, name, slug FROM catalog.exam_category WHERE id = :id")
                .param("id", e.categoryId())
                .query((rs, row) -> new CategoryRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug")))
                .single();
        List<ModeSettings> enabled = modes.enabled(e.id());
        Seo seo = new Seo(e.seoTitle() != null ? e.seoTitle() : e.title(),
                e.seoDescription() != null ? e.seoDescription() : e.shortDescription());
        return new ExamDetail(e.id(), e.title(), e.slug(), e.categoryId(), category, e.shortDescription(), null,
                e.featured(), e.displayOrder(), enabled.stream().map(ModeSettings::mode).toList(), e.instructions(), seo,
                tree(exams.activeTree(e.id())), enabled.stream().map(PublicModeSettings::of).toList());
    }

    static List<SubjectNode> tree(List<TreeRow> rows) {
        Map<UUID, String> subjectTitles = new LinkedHashMap<>();
        Map<UUID, Map<UUID, String>> modulesBySubject = new LinkedHashMap<>();
        Map<UUID, List<TopicNode>> topicsByModule = new LinkedHashMap<>();
        for (TreeRow r : rows) {
            subjectTitles.putIfAbsent(r.subjectId(), r.subjectTitle());
            modulesBySubject.computeIfAbsent(r.subjectId(), k -> new LinkedHashMap<>()).putIfAbsent(r.moduleId(), r.moduleTitle());
            topicsByModule.computeIfAbsent(r.moduleId(), k -> new ArrayList<>())
                    .add(new TopicNode(r.topicId(), r.topicTitle(), r.topicOrder()));
        }
        List<SubjectNode> subjects = new ArrayList<>();
        subjectTitles.forEach((subjectId, title) -> {
            List<ModuleNode> modules = new ArrayList<>();
            modulesBySubject.get(subjectId).forEach((moduleId, moduleTitle) ->
                    modules.add(new ModuleNode(moduleId, moduleTitle, List.copyOf(topicsByModule.get(moduleId)))));
            subjects.add(new SubjectNode(subjectId, title, List.copyOf(modules)));
        });
        return List.copyOf(subjects);
    }

    private ExamSummary summary(ExamRow e) {
        return new ExamSummary(e.id(), e.title(), e.slug(), e.categoryId(), e.shortDescription(), null, e.featured(),
                e.displayOrder(), modes.enabled(e.id()).stream().map(ModeSettings::mode).toList());
    }

    private AdminExam adminView(ExamRow e) {
        ContentCounts counts = exams.counts(e.id());
        return new AdminExam(e.id(), e.title(), e.slug(), e.categoryId(), e.shortDescription(), null, e.featured(),
                e.displayOrder(), modes.enabled(e.id()).stream().map(ModeSettings::mode).toList(), e.status(),
                e.publishedAt(), e.updatedAt(), e.version(), counts.publishedMain(), counts.publishedTrial(),
                counts.topics(), readiness.evaluate(e, counts));
    }

    private AdminExamDetail adminDetail(ExamRow e) {
        ContentCounts counts = exams.counts(e.id());
        List<ModeSettings> all = modes.forExam(e.id());
        return new AdminExamDetail(e.id(), e.title(), e.slug(), e.categoryId(), e.shortDescription(), null,
                e.featured(), e.displayOrder(), all.stream().filter(ModeSettings::enabled).map(ModeSettings::mode).toList(),
                e.status(), e.publishedAt(), e.updatedAt(), e.version(), counts.publishedMain(), counts.publishedTrial(),
                counts.topics(), readiness.evaluate(e, counts), e.instructions(), e.seoTitle(), e.seoDescription(),
                exams.subjectIds(e.id()), all);
    }

    private ExamRow find(UUID id) {
        return exams.findById(id).orElseThrow(ExamService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "Exam not found.");
    }

    private void checkSlug(String slug, UUID examId) {
        if (exams.slugTaken(slug, examId)) {
            throw new ApiException(ProblemCode.SLUG_TAKEN, "The address \"" + slug
                    + "\" is used, or was used before, by another exam. Choose another.");
        }
    }

    /** New exams need an active category; an existing exam may keep its (since archived) category. */
    private void checkCategory(UUID categoryId, UUID currentCategoryId) {
        Optional<String> status = jdbc.sql("SELECT status FROM catalog.exam_category WHERE id = :id")
                .param("id", categoryId).query(String.class).optional();
        if (status.isEmpty()) {
            throw Requests.invalid("categoryId", "category does not exist");
        }
        if (!"ACTIVE".equals(status.get()) && !categoryId.equals(currentCategoryId)) {
            throw Requests.invalid("categoryId", "category is archived");
        }
    }

    private List<UUID> checkSubjects(List<UUID> requested) {
        List<UUID> ids = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (exams.countActiveSubjects(ids) != ids.size()) {
            throw Requests.invalid("subjectIds", "every subject must exist and be active");
        }
        return ids;
    }

    private static ExamFields fields(ExamInput in) {
        return new ExamFields(in.categoryId(), in.title().strip(), in.slug(), orEmpty(in.shortDescription()),
                orEmpty(in.instructions()), blankToNull(in.seoTitle()), blankToNull(in.seoDescription()),
                in.displayOrder(), in.featured());
    }

    private Map<String, Object> eventPayload(ExamRow e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("examId", e.id().toString());
        payload.put("slug", e.slug());
        payload.put("title", e.title());
        payload.put("categoryId", e.categoryId().toString());
        payload.put("displayOrder", e.displayOrder());
        payload.put("status", e.status());
        payload.put("modes", modes.enabled(e.id()).stream().map(ModeSettings::mode).toList());
        payload.put("version", e.version());
        return payload;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
