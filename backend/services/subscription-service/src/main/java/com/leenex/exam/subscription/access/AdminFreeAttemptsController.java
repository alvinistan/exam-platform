package com.leenex.exam.subscription.access;

import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What access looks like across the catalogue, for the admin Access page: each published exam with its free attempts
 * per exam type. The numbers themselves are edited in the exam's setup (step 4), where they belong.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminFreeAttemptsController {

    private final ExamViewRepository exams;

    public AdminFreeAttemptsController(ExamViewRepository exams) {
        this.exams = exams;
    }

    public record ExamFreeAttempts(UUID examId, String title, String slug, List<String> modes,
                                   Map<String, Integer> freeAttempts) {
    }

    @GetMapping("/api/admin/billing/free-attempts")
    public List<ExamFreeAttempts> list() {
        return exams.publishedInOrder().stream()
                .map(e -> new ExamFreeAttempts(e.examId(), e.title(), e.slug(), e.modes(), e.freeAttempts()))
                .toList();
    }
}
