package com.leenex.exam.assessment.progress;

import com.leenex.exam.assessment.progress.ProgressService.ExamProgress;
import com.leenex.exam.assessment.progress.ProgressService.ExamSummary;
import com.leenex.exam.platform.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in student's own progress (contract tag Progress). */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    /** Exams the student has practised, with a one-line summary each. */
    @GetMapping("/exams")
    public List<ExamSummary> exams() {
        return progress.exams(CurrentUser.id());
    }

    /** Per-topic accuracy, level and status, what to practise next, and recent results. */
    @GetMapping("/exams/{examId}")
    public ExamProgress exam(@PathVariable UUID examId) {
        return progress.exam(CurrentUser.id(), examId);
    }
}
