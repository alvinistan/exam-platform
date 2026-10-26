package com.leenex.exam.catalog.content;

import com.leenex.exam.catalog.content.ContentRepository.ModuleRow;
import com.leenex.exam.catalog.content.ContentRepository.SubjectRow;
import com.leenex.exam.catalog.content.ContentRepository.TopicRow;
import com.leenex.exam.catalog.exam.PublishReadiness;
import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reusable content library (FR-CAT-03, FR-CAT-13). Archiving is the only way to retire content, so questions and
 * past attempts keep their topic. A change that would leave a published exam unable to run is refused.
 */
@Service
public class ContentService {

    private final ContentRepository content;
    private final PublishReadiness readiness;
    private final AuditLog audit;

    public ContentService(ContentRepository content, PublishReadiness readiness, AuditLog audit) {
        this.content = content;
        this.readiness = readiness;
        this.audit = audit;
    }

    public record TopicView(UUID id, UUID moduleId, String title, int displayOrder, String status, int version,
                            List<UUID> prerequisiteTopicIds, int publishedQuestionCount, int trialQuestionCount) {
        static TopicView of(TopicRow t) {
            return new TopicView(t.id(), t.moduleId(), t.title(), t.displayOrder(), t.status(), t.version(),
                    t.prerequisiteTopicIds(), t.publishedMain(), t.publishedTrial());
        }
    }

    public record ModuleView(UUID id, UUID subjectId, String title, int displayOrder, String status, int version,
                             List<TopicView> topics) {
    }

    public record SubjectView(UUID id, String title, String code, String description, String status, int version,
                              int linkedExamCount, List<ModuleView> modules) {
    }

    /** The whole library, archived items included, for the admin content screen. */
    @Transactional(readOnly = true)
    public List<SubjectView> library() {
        Map<UUID, List<TopicView>> topicsByModule = new LinkedHashMap<>();
        for (TopicRow t : content.topics()) {
            topicsByModule.computeIfAbsent(t.moduleId(), k -> new ArrayList<>()).add(TopicView.of(t));
        }
        Map<UUID, List<ModuleView>> modulesBySubject = new LinkedHashMap<>();
        for (ModuleRow m : content.modules()) {
            modulesBySubject.computeIfAbsent(m.subjectId(), k -> new ArrayList<>()).add(new ModuleView(m.id(),
                    m.subjectId(), m.title(), m.displayOrder(), m.status(), m.version(),
                    List.copyOf(topicsByModule.getOrDefault(m.id(), List.of()))));
        }
        return content.subjects().stream().map(s -> subjectView(s,
                List.copyOf(modulesBySubject.getOrDefault(s.id(), List.of())))).toList();
    }

    // --- subjects ---------------------------------------------------------------------------------------------------

    @Transactional
    public SubjectView createSubject(UUID actorId, String title, String code, String description) {
        String normalizedCode = blankToNull(code);
        if (content.subjectCodeTaken(normalizedCode, null)) {
            throw Requests.invalid("code", "another subject already uses this code");
        }
        UUID id = UuidV7.generate();
        content.insertSubject(id, title.strip(), normalizedCode, blankToNull(description));
        audit.record(actorId, "SUBJECT_CREATED", "SUBJECT", id, Map.of("title", title.strip()));
        return subjectView(content.subject(id).orElseThrow(), List.of());
    }

    @Transactional
    public SubjectView updateSubject(UUID actorId, UUID id, int version, String title, String code, String description,
                                     String status) {
        SubjectRow before = content.lockSubject(id).orElseThrow(() -> notFound("Subject"));
        checkVersion(before.version(), version);
        String normalizedCode = blankToNull(code);
        if (content.subjectCodeTaken(normalizedCode, id)) {
            throw Requests.invalid("code", "another subject already uses this code");
        }
        String newStatus = status == null ? before.status() : status;
        content.updateSubject(id, version, title.strip(), normalizedCode, blankToNull(description), newStatus);
        readiness.requireStillPublishable(content.publishedExamsUsingSubject(id));
        audit.record(actorId, statusAction("SUBJECT", before.status(), newStatus), "SUBJECT", id, Map.of());
        return library().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    // --- modules ----------------------------------------------------------------------------------------------------

    @Transactional
    public ModuleView createModule(UUID actorId, UUID subjectId, String title, int displayOrder) {
        SubjectRow subject = content.lockSubject(subjectId)
                .orElseThrow(() -> Requests.invalid("subjectId", "subject does not exist"));
        if (!"ACTIVE".equals(subject.status())) {
            throw Requests.invalid("subjectId", "subject is archived");
        }
        UUID id = UuidV7.generate();
        content.insertModule(id, subjectId, title.strip(), displayOrder);
        audit.record(actorId, "MODULE_CREATED", "MODULE", id, Map.of("subjectId", subjectId.toString()));
        return moduleView(id);
    }

    @Transactional
    public ModuleView updateModule(UUID actorId, UUID id, int version, String title, int displayOrder, String status) {
        ModuleRow before = content.lockModule(id).orElseThrow(() -> notFound("Module"));
        checkVersion(before.version(), version);
        String newStatus = status == null ? before.status() : status;
        content.updateModule(id, version, title.strip(), displayOrder, newStatus);
        readiness.requireStillPublishable(content.publishedExamsUsingSubject(before.subjectId()));
        audit.record(actorId, statusAction("MODULE", before.status(), newStatus), "MODULE", id, Map.of());
        return moduleView(id);
    }

    /**
     * The module new topics of a subject go into when an administrator works with Subject → Topics only: the
     * subject's first active module, or a new one called "Topics".
     */
    @Transactional
    public UUID topicModule(UUID actorId, UUID subjectId) {
        SubjectRow subject = content.lockSubject(subjectId)
                .orElseThrow(() -> Requests.invalid("subjectId", "subject does not exist"));
        if (!"ACTIVE".equals(subject.status())) {
            throw Requests.invalid("subjectId", "subject is archived");
        }
        return content.modules().stream()
                .filter(m -> m.subjectId().equals(subjectId) && "ACTIVE".equals(m.status()))
                .min(java.util.Comparator.comparingInt(ModuleRow::displayOrder).thenComparing(ModuleRow::title))
                .map(ModuleRow::id)
                .orElseGet(() -> createModule(actorId, subjectId, "Topics", 0).id());
    }

    // --- topics -----------------------------------------------------------------------------------------------------

    @Transactional
    public TopicView createTopic(UUID actorId, UUID moduleId, String title, int displayOrder, List<UUID> prerequisites) {
        ModuleRow module = content.lockModule(moduleId)
                .orElseThrow(() -> Requests.invalid("moduleId", "module does not exist"));
        if (!"ACTIVE".equals(module.status())) {
            throw Requests.invalid("moduleId", "module is archived");
        }
        UUID id = UuidV7.generate();
        List<UUID> prereqs = checkPrerequisites(id, prerequisites);
        content.insertTopic(id, moduleId, title.strip(), displayOrder);
        content.replacePrerequisites(id, prereqs);
        audit.record(actorId, "TOPIC_CREATED", "TOPIC", id, Map.of("moduleId", moduleId.toString()));
        return TopicView.of(content.topic(id).orElseThrow());
    }

    @Transactional
    public TopicView updateTopic(UUID actorId, UUID id, int version, String title, int displayOrder, String status,
                                 List<UUID> prerequisites) {
        TopicRow before = content.lockTopic(id).orElseThrow(() -> notFound("Topic"));
        checkVersion(before.version(), version);
        List<UUID> prereqs = prerequisites == null ? before.prerequisiteTopicIds() : checkPrerequisites(id, prerequisites);
        String newStatus = status == null ? before.status() : status;
        content.updateTopic(id, version, title.strip(), displayOrder, newStatus);
        content.replacePrerequisites(id, prereqs);
        ModuleRow module = content.module(before.moduleId()).orElseThrow();
        readiness.requireStillPublishable(content.publishedExamsUsingSubject(module.subjectId()));
        audit.record(actorId, statusAction("TOPIC", before.status(), newStatus), "TOPIC", id, Map.of());
        return TopicView.of(content.topic(id).orElseThrow());
    }

    /** Prerequisites must be other existing topics and must not form a loop (FR-CAT-13). */
    private List<UUID> checkPrerequisites(UUID topicId, List<UUID> requested) {
        List<UUID> ids = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (ids.contains(topicId)) {
            throw Requests.invalid("prerequisiteTopicIds", "a topic can't be its own prerequisite");
        }
        if (content.countTopics(ids) != ids.size()) {
            throw Requests.invalid("prerequisiteTopicIds", "every prerequisite must be an existing topic");
        }
        for (UUID prerequisite : ids) {
            if (content.reachable(prerequisite, topicId)) {
                throw Requests.invalid("prerequisiteTopicIds", "this would make topics depend on each other in a loop");
            }
        }
        return ids;
    }

    private ModuleView moduleView(UUID id) {
        ModuleRow m = content.module(id).orElseThrow();
        List<TopicView> topics = content.topics().stream().filter(t -> t.moduleId().equals(id)).map(TopicView::of).toList();
        return new ModuleView(m.id(), m.subjectId(), m.title(), m.displayOrder(), m.status(), m.version(), topics);
    }

    private static SubjectView subjectView(SubjectRow s, List<ModuleView> modules) {
        return new SubjectView(s.id(), s.title(), s.code(), s.description(), s.status(), s.version(), s.linkedExamCount(),
                modules);
    }

    private static void checkVersion(int actual, int expected) {
        if (actual != expected) {
            throw Requests.staleVersion();
        }
    }

    private static String statusAction(String type, String before, String after) {
        if (!before.equals(after)) {
            return type + ("ARCHIVED".equals(after) ? "_ARCHIVED" : "_RESTORED");
        }
        return type + "_UPDATED";
    }

    private static ApiException notFound(String what) {
        return new ApiException(ProblemCode.NOT_FOUND, what + " not found.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
