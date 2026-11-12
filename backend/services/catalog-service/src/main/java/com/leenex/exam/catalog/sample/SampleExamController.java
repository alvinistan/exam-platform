package com.leenex.exam.catalog.sample;

import com.leenex.exam.catalog.exam.ExamViews.AdminExamDetail;
import com.leenex.exam.platform.security.CurrentUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One-click sample exam for administrators (contract tag Admin exams). */
@RestController
@RequestMapping("/api/admin/catalog/sample-exams")
@PreAuthorize("hasRole('ADMIN')")
public class SampleExamController {

    private final SampleExamService samples;

    public SampleExamController(SampleExamService samples) {
        this.samples = samples;
    }

    @PostMapping("/ol-science")
    public ResponseEntity<AdminExamDetail> createOlScience() {
        AdminExamDetail exam = samples.createOlScience(CurrentUser.id());
        return ResponseEntity.created(URI.create("/api/admin/catalog/exams/" + exam.id()))
                .eTag(Integer.toString(exam.version())).body(exam);
    }
}
