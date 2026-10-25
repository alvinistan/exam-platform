package com.leenex.exam.catalog.content;

import com.leenex.exam.catalog.content.ContentService.ModuleView;
import com.leenex.exam.catalog.content.ContentService.SubjectView;
import com.leenex.exam.catalog.content.ContentService.TopicView;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Subjects, modules and topics: the content library shared by every exam (FR-CAT-03). */
@RestController
@RequestMapping("/api/admin/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminContentController {

    private static final String STATUS = "ACTIVE|ARCHIVED";

    private final ContentService content;

    public AdminContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/subjects")
    public List<SubjectView> library() {
        return content.library();
    }

    public record SubjectInput(@NotBlank @Size(min = 2, max = 160) String title,
                               @Size(max = 40) @Pattern(regexp = "^$|^[A-Z0-9][A-Z0-9_-]*$",
                                       message = "use capital letters, digits, hyphens and underscores") String code,
                               @Size(max = 500) String description,
                               @Pattern(regexp = STATUS) String status) {
    }

    @PostMapping("/subjects")
    public ResponseEntity<SubjectView> createSubject(@Valid @RequestBody SubjectInput in) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(content.createSubject(CurrentUser.id(), in.title(), in.code(), in.description()));
    }

    @PutMapping("/subjects/{subjectId}")
    public SubjectView updateSubject(@PathVariable UUID subjectId,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                     @Valid @RequestBody SubjectInput in) {
        return content.updateSubject(CurrentUser.id(), subjectId, Requests.ifMatchVersion(ifMatch), in.title(), in.code(),
                in.description(), in.status());
    }

    public record ModuleInput(@NotNull UUID subjectId, @NotBlank @Size(min = 2, max = 160) String title,
                              @Min(0) @Max(100_000) Integer displayOrder) {
    }

    public record ModuleUpdate(@NotBlank @Size(min = 2, max = 160) String title,
                               @Min(0) @Max(100_000) Integer displayOrder,
                               @Pattern(regexp = STATUS) String status) {
    }

    @PostMapping("/modules")
    public ResponseEntity<ModuleView> createModule(@Valid @RequestBody ModuleInput in) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(content.createModule(CurrentUser.id(), in.subjectId(), in.title(), orZero(in.displayOrder())));
    }

    @PutMapping("/modules/{moduleId}")
    public ModuleView updateModule(@PathVariable UUID moduleId,
                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                   @Valid @RequestBody ModuleUpdate in) {
        return content.updateModule(CurrentUser.id(), moduleId, Requests.ifMatchVersion(ifMatch), in.title(),
                orZero(in.displayOrder()), in.status());
    }

    public record TopicInput(@NotNull UUID moduleId, @NotBlank @Size(min = 2, max = 160) String title,
                             @Min(0) @Max(100_000) Integer displayOrder,
                             @Size(max = 20) List<@NotNull UUID> prerequisiteTopicIds) {
    }

    public record TopicUpdate(@NotBlank @Size(min = 2, max = 160) String title,
                              @Min(0) @Max(100_000) Integer displayOrder,
                              @Pattern(regexp = STATUS) String status,
                              @Size(max = 20) List<@NotNull UUID> prerequisiteTopicIds) {
    }

    @PostMapping("/topics")
    public ResponseEntity<TopicView> createTopic(@Valid @RequestBody TopicInput in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(content.createTopic(CurrentUser.id(), in.moduleId(),
                in.title(), orZero(in.displayOrder()), in.prerequisiteTopicIds()));
    }

    @PutMapping("/topics/{topicId}")
    public TopicView updateTopic(@PathVariable UUID topicId,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                 @Valid @RequestBody TopicUpdate in) {
        return content.updateTopic(CurrentUser.id(), topicId, Requests.ifMatchVersion(ifMatch), in.title(),
                orZero(in.displayOrder()), in.status(), in.prerequisiteTopicIds());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
