package com.leenex.exam.catalog.category;

import com.leenex.exam.catalog.category.CategoryRepository.CategoryRow;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/** Category administration (FR-CAT-01). */
@RestController
@RequestMapping("/api/admin/catalog/categories")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private final CategoryService categories;

    public AdminCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    public record AdminCategory(UUID id, String name, String slug, String description, int displayOrder, String status,
                                int version, int examCount, int publishedExamCount) {
        static AdminCategory of(CategoryRow r) {
            return new AdminCategory(r.id(), r.name(), r.slug(), r.description(), r.displayOrder(), r.status(),
                    r.version(), r.examCount(), r.publishedExamCount());
        }
    }

    public record CategoryInput(
            @NotBlank @Size(min = 2, max = 80) String name,
            @NotBlank @Size(max = 80) @Pattern(regexp = SLUG, message = "use lower-case letters, digits and single hyphens") String slug,
            @Size(max = 500) String description,
            @Min(0) @Max(100_000) Integer displayOrder) {
        CategoryService.CategoryInput toService() {
            return new CategoryService.CategoryInput(name, slug, description, displayOrder);
        }
    }

    @GetMapping
    public List<AdminCategory> list() {
        return categories.list(false).stream().map(AdminCategory::of).toList();
    }

    @PostMapping
    public ResponseEntity<AdminCategory> create(@Valid @RequestBody CategoryInput input) {
        AdminCategory created = AdminCategory.of(categories.create(CurrentUser.id(), input.toService()));
        return ResponseEntity.created(URI.create("/api/admin/catalog/categories/" + created.id()))
                .eTag(Integer.toString(created.version())).body(created);
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<AdminCategory> update(@PathVariable UUID categoryId,
                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @Valid @RequestBody CategoryInput input) {
        AdminCategory updated = AdminCategory.of(categories.update(CurrentUser.id(), categoryId,
                Requests.ifMatchVersion(ifMatch), input.toService()));
        return ResponseEntity.ok().eTag(Integer.toString(updated.version())).body(updated);
    }

    @PostMapping("/{categoryId}/archive")
    public AdminCategory archive(@PathVariable UUID categoryId) {
        return AdminCategory.of(categories.setStatus(CurrentUser.id(), categoryId, "ARCHIVED"));
    }

    @PostMapping("/{categoryId}/restore")
    public AdminCategory restore(@PathVariable UUID categoryId) {
        return AdminCategory.of(categories.setStatus(CurrentUser.id(), categoryId, "ACTIVE"));
    }
}
