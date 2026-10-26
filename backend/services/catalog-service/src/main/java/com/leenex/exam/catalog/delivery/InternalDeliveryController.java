package com.leenex.exam.catalog.delivery;

import com.leenex.exam.catalog.delivery.DeliveryRepository.Content;
import com.leenex.exam.catalog.delivery.DeliveryRepository.PoolQuestion;
import com.leenex.exam.catalog.exam.ExamRepository;
import com.leenex.exam.catalog.exam.ExamRepository.TreeRow;
import com.leenex.exam.catalog.exam.ExamRow;
import com.leenex.exam.catalog.exam.ModeRepository;
import com.leenex.exam.catalog.exam.ModeSettings;
import com.leenex.exam.catalog.media.MediaService;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Question delivery for assessment-service (ADR-008). Service tokens with scope {@code catalog.internal} only; the
 * gateway never routes {@code /internal/**}.
 *
 * <ul>
 *   <li>{@code GET /internal/exams/{id}/question-pool}: the exam's settings, its topics and the published questions
 *       (with answer keys) a session may be chosen from. assessment-service chooses, because it knows what each
 *       student has seen.</li>
 *   <li>{@code POST /internal/question-versions}: display content for the exact versions an attempt holds.</li>
 * </ul>
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_catalog.internal')")
public class InternalDeliveryController {

    private final ExamRepository exams;
    private final ModeRepository modes;
    private final DeliveryRepository delivery;
    private final MediaService media;

    public InternalDeliveryController(ExamRepository exams, ModeRepository modes, DeliveryRepository delivery,
                                      MediaService media) {
        this.exams = exams;
        this.modes = modes;
        this.delivery = delivery;
        this.media = media;
    }

    public record PoolTopic(UUID id, String title, UUID subjectId, String subjectTitle) {
    }

    public record Pool(UUID examId, String title, String slug, String status, List<ModeSettings> modes,
                       List<PoolTopic> topics, List<PoolQuestion> questions) {
    }

    /**
     * @param topicIds the topics a student chose; empty means every active topic of the exam. Topics that don't
     *                 belong to the exam are ignored.
     */
    @GetMapping("/internal/exams/{examId}/question-pool")
    @Transactional(readOnly = true)
    public Pool pool(@PathVariable UUID examId, @RequestParam(required = false) List<UUID> topicIds) {
        ExamRow exam = exams.findById(examId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Exam not found."));
        List<TreeRow> tree = exams.activeTree(examId);
        List<PoolTopic> topics = tree.stream()
                .map(t -> new PoolTopic(t.topicId(), t.topicTitle(), t.subjectId(), t.subjectTitle())).toList();
        Set<UUID> wanted = new LinkedHashSet<>();
        for (PoolTopic t : topics) {
            if (topicIds == null || topicIds.isEmpty() || topicIds.contains(t.id())) {
                wanted.add(t.id());
            }
        }
        return new Pool(exam.id(), exam.title(), exam.slug(), exam.status(), modes.forExam(examId), topics,
                delivery.pool(wanted));
    }

    public record VersionsInput(@NotNull @Size(min = 1, max = 500) List<@NotNull UUID> versionIds) {
    }

    public record DeliveredOption(String label, String bodyHtml, String mediaUrl, String mediaAlt) {
    }

    public record Delivered(UUID versionId, UUID questionId, String stemHtml, String stemMediaUrl, String stemMediaAlt,
                            List<DeliveredOption> options, String explanationHtml, List<String> keyPoints,
                            JsonNode definitions) {
    }

    @PostMapping("/internal/question-versions")
    @Transactional(readOnly = true)
    public List<Delivered> versions(@Valid @RequestBody VersionsInput input) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(input.versionIds()));
        List<Content> found = delivery.content(ids);
        if (found.size() != ids.size()) {
            throw Requests.invalid("versionIds", "every version must exist and be published");
        }
        return found.stream().map(c -> new Delivered(c.versionId(), c.questionId(), c.stemHtml(),
                media.signedUrl(c.stemMediaKey()), c.stemMediaAlt(),
                c.options().stream().map(o -> new DeliveredOption(o.label(), o.bodyHtml(), media.signedUrl(o.mediaKey()),
                        o.mediaAlt())).toList(),
                c.explanationHtml(), c.keyPoints(), c.definitions())).toList();
    }
}
