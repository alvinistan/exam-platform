package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.category.AdminCategoryController;
import com.leenex.exam.catalog.exam.ExamViews.AdminExam;
import com.leenex.exam.catalog.exam.ExamViews.AdminExamDetail;
import com.leenex.exam.catalog.exam.ModeSettings.DifficultyMix;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.catalog.support.Requests.Page;
import com.leenex.exam.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exam administration (contract tag Admin exams). */
@RestController
@RequestMapping("/api/admin/catalog/exams")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExamController {

    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final ExamService exams;

    public AdminExamController(ExamService exams) {
        this.exams = exams;
    }

    public record ExamInput(
            @NotBlank @Size(min = 2, max = 160) String title,
            @NotBlank @Size(max = 80)
            @Pattern(regexp = AdminCategoryController.SLUG, message = "use lower-case letters, digits and single hyphens")
            String slug,
            @NotNull UUID categoryId,
            @Size(max = 300) String shortDescription,
            @Size(max = 5000) String instructions,
            @Size(max = 70) String seoTitle,
            @Size(max = 160) String seoDescription,
            @Min(1) @Max(100_000) Integer displayOrder,
            Boolean featured,
            @Size(max = 50) List<@NotNull UUID> subjectIds) {
        ExamService.ExamInput toService() {
            return new ExamService.ExamInput(title, slug, categoryId, shortDescription, instructions, seoTitle,
                    seoDescription, displayOrder, Boolean.TRUE.equals(featured), subjectIds);
        }
    }

    @GetMapping
    public Page<AdminExam> list(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) UUID categoryId) {
        Requests.checkPaging(page, size, q);
        if (status != null && !STATUSES.contains(status)) {
            throw Requests.invalid("status", "must be DRAFT, PUBLISHED or ARCHIVED");
        }
        return exams.adminSearch(q, status, categoryId, page, size);
    }

    @PostMapping
    public ResponseEntity<AdminExamDetail> create(@Valid @RequestBody ExamInput input) {
        AdminExamDetail created = exams.create(CurrentUser.id(), input.toService());
        return ResponseEntity.created(URI.create("/api/admin/catalog/exams/" + created.id()))
                .eTag(Integer.toString(created.version())).body(created);
    }

    @GetMapping("/{examId}")
    public ResponseEntity<AdminExamDetail> get(@PathVariable UUID examId) {
        AdminExamDetail exam = exams.adminGet(examId);
        return ResponseEntity.ok().eTag(Integer.toString(exam.version())).body(exam);
    }

    /** Replaces the exam's details and subject links. Send every field; If-Match carries the version you edited. */
    @PutMapping("/{examId}")
    public ResponseEntity<AdminExamDetail> update(@PathVariable UUID examId,
                                                  @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                  @Valid @RequestBody ExamInput input) {
        AdminExamDetail exam = exams.update(CurrentUser.id(), examId, Requests.ifMatchVersion(ifMatch), input.toService());
        return ResponseEntity.ok().eTag(Integer.toString(exam.version())).body(exam);
    }

    @PostMapping("/{examId}/publish")
    public AdminExamDetail publish(@PathVariable UUID examId) {
        return exams.publish(CurrentUser.id(), examId);
    }

    @PostMapping("/{examId}/archive")
    public AdminExamDetail archive(@PathVariable UUID examId) {
        return exams.archive(CurrentUser.id(), examId);
    }

    public record MixInput(@NotNull @Min(0) @Max(100) Integer simple, @NotNull @Min(0) @Max(100) Integer medium,
                           @NotNull @Min(0) @Max(100) Integer hard) {
    }

    public record ModeInput(
            @NotNull Boolean enabled,
            @NotNull @Min(1) @Max(500) Integer defaultCount,
            @NotNull @Min(1) @Max(500) Integer minCount,
            @NotNull @Min(1) @Max(500) Integer maxCount,
            @Min(60) @Max(43_200) Integer timeLimitSeconds,
            @Min(10) @Max(3600) Integer perQuestionSeconds,
            @NotNull @Valid MixInput difficultyMix,
            @NotNull Boolean shuffleQuestions,
            @NotNull Boolean shuffleOptions,
            @NotNull @Pattern(regexp = "IMMEDIATE|AFTER_SUBMIT") String feedbackTiming,
            @NotNull @Pattern(regexp = "SCORE_ONLY|SCORE_AND_ANSWERS|FULL") String resultVisibility,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal negativeMark,
            @Min(1) @Max(100) Integer passMarkPct,
            @NotNull Boolean allowTopicChoice,
            Boolean adaptiveEnabled,
            @Min(0) @Max(1000) Integer maxRetakes) {
    }

    @GetMapping("/{examId}/modes")
    public List<ModeSettings> modes(@PathVariable UUID examId) {
        return exams.adminGet(examId).modeSettings();
    }

    @PutMapping("/{examId}/modes/{mode}")
    public ModeSettings configureMode(@PathVariable UUID examId, @PathVariable String mode,
                                      @Valid @RequestBody ModeInput input) {
        if (!ModeSettings.MODES.contains(mode)) {
            throw Requests.invalid("mode", "must be PRACTICE, MOCK or USER_FRIENDLY");
        }
        validateModeRules(mode, input);
        ModeSettings settings = new ModeSettings(mode, input.enabled(), input.defaultCount(), input.minCount(),
                input.maxCount(), input.timeLimitSeconds(), input.perQuestionSeconds(),
                new DifficultyMix(input.difficultyMix().simple(), input.difficultyMix().medium(),
                        input.difficultyMix().hard()),
                input.shuffleQuestions(), input.shuffleOptions(), input.feedbackTiming(), input.resultVisibility(),
                input.negativeMark().setScale(2, RoundingMode.HALF_UP), input.passMarkPct(), input.allowTopicChoice(),
                Boolean.TRUE.equals(input.adaptiveEnabled()), input.maxRetakes(), 0, true);
        return exams.configureMode(CurrentUser.id(), examId, settings);
    }

    /** Rules across fields (the database enforces the same ones as a last line of defence). */
    static void validateModeRules(String mode, ModeInput in) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (in.minCount() > in.defaultCount()) {
            errors.add(Map.of("field", "minCount", "message", "must not be more than the default count"));
        }
        if (in.defaultCount() > in.maxCount()) {
            errors.add(Map.of("field", "maxCount", "message", "must not be less than the default count"));
        }
        MixInput mix = in.difficultyMix();
        if (mix.simple() + mix.medium() + mix.hard() != 100) {
            errors.add(Map.of("field", "difficultyMix", "message", "percentages must add up to 100"));
        }
        if ("MOCK".equals(mode)) {
            if (in.timeLimitSeconds() == null) {
                errors.add(Map.of("field", "timeLimitSeconds", "message", "a mock exam needs a time limit"));
            }
            if (!"AFTER_SUBMIT".equals(in.feedbackTiming())) {
                errors.add(Map.of("field", "feedbackTiming", "message", "a mock exam shows answers only after submission"));
            }
        }
        if (in.negativeMark().stripTrailingZeros().scale() > 2) {
            errors.add(Map.of("field", "negativeMark", "message", "use at most two decimal places"));
        }
        if (!errors.isEmpty()) {
            throw Requests.invalid(errors);
        }
    }
}
