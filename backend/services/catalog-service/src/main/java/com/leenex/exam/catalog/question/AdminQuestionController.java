package com.leenex.exam.catalog.question;

import com.leenex.exam.catalog.question.QuestionContent.Definition;
import com.leenex.exam.catalog.question.QuestionSearch.Filters;
import com.leenex.exam.catalog.question.QuestionSearch.Summary;
import com.leenex.exam.catalog.question.QuestionService.QuestionDetail;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.catalog.support.Requests.Page;
import com.leenex.exam.catalog.support.Requests.PageMeta;
import com.leenex.exam.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
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

/** The question bank (contract tag Admin questions). */
@RestController
@RequestMapping("/api/admin/catalog/questions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminQuestionController {

    private static final Set<String> STATUSES = Set.of("DRAFT", "REVIEWED", "PUBLISHED", "ARCHIVED");
    private static final Set<String> DIFFICULTIES = Set.of("SIMPLE", "MEDIUM", "HARD");
    private static final Set<String> SORTS = Set.of("updatedAt", "-updatedAt", "difficulty");

    private final QuestionService questions;
    private final QuestionSearch search;

    public AdminQuestionController(QuestionService questions, QuestionSearch search) {
        this.questions = questions;
        this.search = search;
    }

    public record OptionInput(@Size(max = 4000) String bodyHtml, @Size(max = 80) String mediaKey,
                              @Size(max = 250) String mediaAlt, @NotNull Boolean isCorrect, UUID misconceptionId) {
    }

    public record DefinitionInput(@Size(max = 120) String term, @Size(max = 1000) String meaning) {
    }

    public record QuestionInput(
            @NotNull UUID topicId,
            Bank bank,
            @NotNull @Pattern(regexp = "SINGLE|MULTI") String answerType,
            @Size(max = 20_000) String stemHtml,
            @Size(max = 80) String stemMediaKey,
            @Size(max = 250) String stemMediaAlt,
            @NotNull @Size(max = 5) List<@Valid @NotNull OptionInput> options,
            @Size(max = 20_000) String explanationHtml,
            @Size(max = 20) List<@Valid @NotNull DefinitionInput> definitions,
            @Size(max = 10) List<@NotNull @Size(max = 300) String> keyPoints,
            @NotNull @Pattern(regexp = "SIMPLE|MEDIUM|HARD") String difficulty,
            @NotNull @Min(10) @Max(600) Integer estSeconds,
            @DecimalMin(value = "0.01") @DecimalMax("100") BigDecimal marks,
            @Size(max = 20) List<@NotNull @Size(max = 60) String> tags) {

        QuestionService.Input toService() {
            return new QuestionService.Input(topicId, answerType, stemHtml, stemMediaKey, stemMediaAlt,
                    options.stream().map(o -> new QuestionService.OptionInput(o.bodyHtml(), o.mediaKey(), o.mediaAlt(),
                            o.isCorrect(), o.misconceptionId())).toList(),
                    explanationHtml,
                    definitions == null ? List.of() : definitions.stream().map(d -> new Definition(d.term(), d.meaning())).toList(),
                    keyPoints, difficulty, estSeconds, marks, tags);
        }
    }

    @GetMapping
    public Page<Summary> search(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) String tag,
                                @RequestParam(required = false) Bank bank,
                                @RequestParam(required = false) UUID examId,
                                @RequestParam(required = false) UUID subjectId,
                                @RequestParam(required = false) UUID moduleId,
                                @RequestParam(required = false) UUID topicId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String difficulty,
                                @RequestParam(required = false) String sort) {
        Requests.checkPaging(page, size, null);
        if (q != null && q.length() > 200) {
            throw Requests.invalid("q", "search text is too long");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw Requests.invalid("status", "must be DRAFT, REVIEWED, PUBLISHED or ARCHIVED");
        }
        if (difficulty != null && !DIFFICULTIES.contains(difficulty)) {
            throw Requests.invalid("difficulty", "must be SIMPLE, MEDIUM or HARD");
        }
        if (sort != null && !SORTS.contains(sort)) {
            throw Requests.invalid("sort", "must be updatedAt, -updatedAt or difficulty");
        }
        QuestionSearch.Result result = search.search(
                new Filters(q, tag, bank, examId, subjectId, moduleId, topicId, status, difficulty, sort), page, size);
        return new Page<>(result.items(), PageMeta.of(page, size, result.total()));
    }

    @PostMapping
    public ResponseEntity<QuestionDetail> create(@Valid @RequestBody QuestionInput input) {
        if (input.bank() == null) {
            throw Requests.invalid("bank", "choose the Trial or Main bank");
        }
        QuestionDetail created = questions.create(CurrentUser.id(), input.bank(), input.toService());
        return ResponseEntity.created(URI.create("/api/admin/catalog/questions/" + created.id()))
                .eTag(Integer.toString(created.version())).body(created);
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<QuestionDetail> get(@PathVariable UUID questionId) {
        QuestionDetail q = questions.get(questionId);
        return ResponseEntity.ok().eTag(Integer.toString(q.version())).body(q);
    }

    @PutMapping("/{questionId}")
    public ResponseEntity<QuestionDetail> save(@PathVariable UUID questionId,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @Valid @RequestBody QuestionInput input) {
        QuestionDetail q = questions.save(CurrentUser.id(), questionId, Requests.ifMatchVersion(ifMatch), input.bank(),
                input.toService());
        return ResponseEntity.ok().eTag(Integer.toString(q.version())).body(q);
    }

    public record Transition(@NotNull @Pattern(regexp = "DRAFT|REVIEWED|PUBLISHED|ARCHIVED") String to) {
    }

    @PostMapping("/{questionId}/transitions")
    public ResponseEntity<QuestionDetail> transition(@PathVariable UUID questionId, @Valid @RequestBody Transition t) {
        QuestionDetail q = questions.transition(CurrentUser.id(), questionId, t.to());
        return ResponseEntity.ok().eTag(Integer.toString(q.version())).body(q);
    }

    @PostMapping("/{questionId}/discard-draft")
    public ResponseEntity<QuestionDetail> discardDraft(@PathVariable UUID questionId) {
        QuestionDetail q = questions.discardDraft(CurrentUser.id(), questionId);
        return ResponseEntity.ok().eTag(Integer.toString(q.version())).body(q);
    }

    public record BulkAction(
            @NotEmpty @Size(max = 200) List<@NotNull UUID> questionIds,
            @NotNull @Pattern(regexp = "SET_STATUS|MOVE_TOPIC|CHANGE_BANK") String action,
            @Pattern(regexp = "DRAFT|REVIEWED|PUBLISHED|ARCHIVED") String status,
            UUID topicId,
            Bank bank) {
    }

    @PostMapping("/bulk")
    public QuestionService.BulkResult bulk(@Valid @RequestBody BulkAction b) {
        if ("SET_STATUS".equals(b.action()) && b.status() == null) {
            throw Requests.invalid("status", "choose the new status");
        }
        if ("CHANGE_BANK".equals(b.action()) && b.bank() == null) {
            throw Requests.invalid("bank", "choose the bank to move to");
        }
        return questions.bulk(CurrentUser.id(), b.questionIds(), b.action(), b.status(), b.topicId(), b.bank());
    }
}
