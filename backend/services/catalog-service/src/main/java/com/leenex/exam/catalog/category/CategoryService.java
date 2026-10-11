package com.leenex.exam.catalog.category;

import com.leenex.exam.catalog.category.CategoryRepository.CategoryRow;
import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final AuditLog audit;

    public CategoryService(CategoryRepository categories, AuditLog audit) {
        this.categories = categories;
        this.audit = audit;
    }

    public record CategoryInput(String name, String slug, String description, Integer displayOrder) {
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> list(boolean activeOnly) {
        return categories.findAll(activeOnly);
    }

    @Transactional(readOnly = true)
    public CategoryRow get(UUID id) {
        return categories.findById(id).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Category not found."));
    }

    @Transactional
    public CategoryRow create(UUID actorId, CategoryInput input) {
        if (categories.slugTaken(input.slug(), null)) {
            throw new ApiException(ProblemCode.SLUG_TAKEN, "Another category already uses the URL name \"" + input.slug() + "\".");
        }
        UUID id = UuidV7.generate();
        categories.insert(id, input.name().strip(), input.slug(), blankToNull(input.description()), orZero(input.displayOrder()));
        audit.record(actorId, "CATEGORY_CREATED", "CATEGORY", id, Map.of("slug", input.slug()));
        return get(id);
    }

    @Transactional
    public CategoryRow update(UUID actorId, UUID id, int expectedVersion, CategoryInput input) {
        categories.lock(id);
        CategoryRow before = get(id);
        if (before.version() != expectedVersion) {
            throw Requests.staleVersion();
        }
        if (categories.slugTaken(input.slug(), id)) {
            throw new ApiException(ProblemCode.SLUG_TAKEN, "Another category already uses the URL name \"" + input.slug() + "\".");
        }
        categories.update(id, expectedVersion, input.name().strip(), input.slug(), blankToNull(input.description()),
                orZero(input.displayOrder()));
        Map<String, Object> summary = new LinkedHashMap<>();
        if (!before.slug().equals(input.slug())) {
            summary.put("slug", Map.of("from", before.slug(), "to", input.slug()));
        }
        audit.record(actorId, "CATEGORY_UPDATED", "CATEGORY", id, summary);
        return get(id);
    }

    /** A category with published exams can't be archived: students would lose the exams' category (FR-CAT-01). */
    @Transactional
    public CategoryRow setStatus(UUID actorId, UUID id, String status) {
        categories.lock(id);
        CategoryRow before = get(id);
        if (before.status().equals(status)) {
            return before;
        }
        if ("ARCHIVED".equals(status) && before.publishedExamCount() > 0) {
            throw new ApiException(ProblemCode.CONFLICT, "This category has " + before.publishedExamCount()
                    + " published exam(s). Move or archive them first.");
        }
        categories.setStatus(id, status);
        audit.record(actorId, "ARCHIVED".equals(status) ? "CATEGORY_ARCHIVED" : "CATEGORY_RESTORED", "CATEGORY", id, Map.of());
        return get(id);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
