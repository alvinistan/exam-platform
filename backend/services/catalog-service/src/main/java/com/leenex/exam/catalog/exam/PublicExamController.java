package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.exam.ExamViews.ExamDetail;
import com.leenex.exam.catalog.exam.ExamViews.ExamSummary;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.catalog.support.Requests.Page;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public catalogue (contract tag Catalogue). Published exams only; never any question content (FR-CAT-08). */
@RestController
@RequestMapping("/api/exams")
public class PublicExamController {

    private final ExamService exams;

    public PublicExamController(ExamService exams) {
        this.exams = exams;
    }

    @GetMapping
    public Page<ExamSummary> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "25") int size,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String category,
                                  @RequestParam(required = false) Boolean featured) {
        Requests.checkPaging(page, size, q);
        return exams.publicSearch(q, category, featured, page, size);
    }

    /** 200 with the exam, 301 to the current address after a rename, 404 NOT_FOUND or EXAM_UNAVAILABLE. */
    @GetMapping("/by-slug/{slug}")
    public ResponseEntity<ExamDetail> bySlug(@PathVariable String slug) {
        if (slug.length() > 80 || !slug.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            throw new ApiException(ProblemCode.NOT_FOUND,
                    "No exam has this address.");
        }
        return switch (exams.resolveSlug(slug)) {
            case ExamService.SlugResolution.Found found -> ResponseEntity.ok().body(found.exam());
            case ExamService.SlugResolution.Moved moved -> ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .location(URI.create("/api/exams/by-slug/" + moved.currentSlug())).build();
        };
    }
}
