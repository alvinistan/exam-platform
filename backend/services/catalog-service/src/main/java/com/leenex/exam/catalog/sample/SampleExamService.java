package com.leenex.exam.catalog.sample;

import com.leenex.exam.catalog.category.CategoryRepository.CategoryRow;
import com.leenex.exam.catalog.category.CategoryService;
import com.leenex.exam.catalog.category.CategoryService.CategoryInput;
import com.leenex.exam.catalog.content.ContentService;
import com.leenex.exam.catalog.exam.ExamRepository;
import com.leenex.exam.catalog.exam.ExamService;
import com.leenex.exam.catalog.exam.ExamViews.AdminExamDetail;
import com.leenex.exam.catalog.exam.ModeSettings;
import com.leenex.exam.catalog.importing.ImportFile;
import com.leenex.exam.catalog.importing.ImportService;
import com.leenex.exam.catalog.question.Bank;
import com.leenex.exam.catalog.question.QuestionService;
import com.leenex.exam.catalog.support.AuditLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a complete, clearly labelled sample exam in one step, so an administrator can see what a finished setup
 * looks like and try it as a student. It is ordinary data: it can be edited, published or archived like any exam.
 *
 * <p>The exam is left as a draft; publishing it stays the administrator's decision.
 */
@Service
public class SampleExamService {

    static final String CATEGORY_SLUG = "gce-ordinary-level";
    static final String BASE_SLUG = "ol-science-sample";
    private static final String QUESTIONS = "sample/ol-science-questions.csv";

    private final CategoryService categories;
    private final ContentService content;
    private final ExamService exams;
    private final ExamRepository examRepository;
    private final ImportService imports;
    private final QuestionService questions;
    private final AuditLog audit;

    public SampleExamService(CategoryService categories, ContentService content, ExamService exams,
                             ExamRepository examRepository, ImportService imports, QuestionService questions,
                             AuditLog audit) {
        this.categories = categories;
        this.content = content;
        this.exams = exams;
        this.examRepository = examRepository;
        this.imports = imports;
        this.questions = questions;
        this.audit = audit;
    }

    @Transactional
    public AdminExamDetail createOlScience(UUID actor) {
        List<ImportFile.Row> rows = rows();
        UUID categoryId = category(actor).id();

        // Subject → modules → topics, in the order the questions file lists them.
        Map<String, Set<String>> topicsByModule = new LinkedHashMap<>();
        for (ImportFile.Row row : rows) {
            topicsByModule.computeIfAbsent(row.get("module"), k -> new LinkedHashSet<>()).add(row.get("topic"));
        }
        UUID subjectId = content.createSubject(actor, rows.getFirst().get("subject"), null,
                "Sample content for the O/L Science sample exam.").id();
        int moduleOrder = 1;
        for (Map.Entry<String, Set<String>> module : topicsByModule.entrySet()) {
            UUID moduleId = content.createModule(actor, subjectId, module.getKey(), moduleOrder++).id();
            int topicOrder = 1;
            for (String topic : module.getValue()) {
                content.createTopic(actor, moduleId, topic, topicOrder++, List.of());
            }
        }

        AdminExamDetail exam = exams.create(actor, new ExamService.ExamInput(
                "O/L Science Revision (sample)", freeSlug(), categoryId,
                "Sample exam: G.C.E. O/L Science questions on ten topics, each at Easy, Medium and Hard level.",
                "This is a sample exam made by Leenex Labs to show how an exam is set up. Edit it, publish it to try it "
                        + "as a student, or archive it when you no longer need it.",
                null, null, null, false, List.of(subjectId)));

        List<UUID> created = imports.createFromRows(actor, exam.id(), Bank.MAIN, rows);
        for (UUID id : created) {
            questions.transition(actor, id, "PUBLISHED");
        }
        exams.configureMode(actor, exam.id(), practice("PRACTICE"));
        exams.configureMode(actor, exam.id(), mock());
        exams.configureMode(actor, exam.id(), practice("USER_FRIENDLY"));
        int questionCount = created.size();
        audit.record(actor, "SAMPLE_EXAM_CREATED", "EXAM", exam.id(), Map.of("questions", questionCount));
        return exams.adminGet(exam.id());
    }

    private CategoryRow category(UUID actor) {
        return categories.list(false).stream().filter(c -> CATEGORY_SLUG.equals(c.slug())).findFirst()
                .map(c -> "ACTIVE".equals(c.status()) ? c : categories.setStatus(actor, c.id(), "ACTIVE"))
                .orElseGet(() -> categories.create(actor, new CategoryInput("G.C.E. Ordinary Level", CATEGORY_SLUG,
                        "Sri Lankan G.C.E. Ordinary Level examinations.", null)));
    }

    /** ol-science-sample, then ol-science-sample-2, -3 … (old addresses stay reserved, so this always moves on). */
    private String freeSlug() {
        String slug = BASE_SLUG;
        for (int n = 2; examRepository.slugTaken(slug, null); n++) {
            slug = BASE_SLUG + "-" + n;
        }
        return slug;
    }

    /** Practice or Adaptive: up to 30 questions, answers shown straight away, 3 free attempts. */
    private static ModeSettings practice(String mode) {
        ModeSettings s = ModeSettings.suggested(mode);
        return new ModeSettings(mode, true, 10, 1, 30, null, null, s.difficultyMix(), true, true, "IMMEDIATE",
                s.resultVisibility(), s.negativeMark(), null, true, s.adaptiveEnabled(), null, 3, 0, true);
    }

    /** Mock: up to 30 questions, at most 60 minutes, answers after submitting, 1 free attempt. */
    private static ModeSettings mock() {
        ModeSettings s = ModeSettings.suggested("MOCK");
        return new ModeSettings("MOCK", true, 30, 1, 30, 60 * 60, null, s.difficultyMix(), true, true, "AFTER_SUBMIT",
                s.resultVisibility(), s.negativeMark(), 50, true, false, null, 1, 0, true);
    }

    private static List<ImportFile.Row> rows() {
        try (InputStream in = new ClassPathResource(QUESTIONS).getInputStream()) {
            return new ArrayList<>(ImportFile.read(QUESTIONS, in.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ImportFile.FormatException e) {
            throw new IllegalStateException("The sample questions file is invalid", e);
        }
    }
}
