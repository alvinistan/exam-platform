package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.attempt.AttemptService.AnswerInput;
import com.leenex.exam.assessment.attempt.AttemptService.StartInput;
import com.leenex.exam.assessment.attempt.AttemptService.Started;
import com.leenex.exam.assessment.attempt.AttemptViews.AttemptSummary;
import com.leenex.exam.assessment.attempt.AttemptViews.AttemptView;
import com.leenex.exam.assessment.attempt.AttemptViews.QuestionView;
import com.leenex.exam.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A signed-in student's own attempts (contract tag Attempts). Other students' attempts answer 404. */
@RestController
@RequestMapping("/api/attempts")
public class AttemptController {

    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    /**
     * @param sizeBy        COUNT (give {@code questionCount}) or TIME (give {@code minutes})
     * @param topicIds      empty or missing: every topic of the exam
     */
    public record StartRequest(@NotNull UUID examId,
                               @NotNull @Pattern(regexp = "PRACTICE|MOCK|USER_FRIENDLY") String mode,
                               @Size(max = 100) List<@NotNull UUID> topicIds,
                               @NotNull @Pattern(regexp = "COUNT|TIME") String sizeBy,
                               @Min(1) @Max(500) Integer questionCount,
                               @Min(1) @Max(1440) Integer minutes) {
    }

    /** {@code selected}: display letters; null leaves the answer as it is (e.g. only flagging). */
    public record AnswerRequest(@Size(max = 5) List<@NotNull @Pattern(regexp = "[A-Ea-e]") String> selected,
                                Boolean flagged, @Min(0) Integer timeSpentMs) {
    }

    @PostMapping
    public ResponseEntity<AttemptView> start(@Valid @RequestBody StartRequest in,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        Started started = attempts.start(CurrentUser.id(), new StartInput(in.examId(), in.mode(),
                in.topicIds() == null ? List.of() : in.topicIds(), in.sizeBy(), in.questionCount(), in.minutes()), key);
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.view());
    }

    @GetMapping
    public List<AttemptSummary> history(@RequestParam(required = false) UUID examId) {
        return attempts.history(CurrentUser.id(), examId);
    }

    @GetMapping("/{attemptId}")
    public AttemptView get(@PathVariable UUID attemptId) {
        return attempts.view(CurrentUser.id(), attemptId);
    }

    @PutMapping("/{attemptId}/answers/{position}")
    public QuestionView answer(@PathVariable UUID attemptId, @PathVariable int position,
                               @Valid @RequestBody AnswerRequest in) {
        return attempts.answer(CurrentUser.id(), attemptId, position,
                new AnswerInput(in.selected(), in.flagged(), in.timeSpentMs()));
    }

    @PostMapping("/{attemptId}/submit")
    public AttemptView submit(@PathVariable UUID attemptId) {
        return attempts.submit(CurrentUser.id(), attemptId);
    }
}
