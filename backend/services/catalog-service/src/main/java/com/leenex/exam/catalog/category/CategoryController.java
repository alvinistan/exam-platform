package com.leenex.exam.catalog.category;

import com.leenex.exam.catalog.category.CategoryRepository.CategoryRow;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public category list for browsing and the landing page (FR-CAT-11, FR-CAT-14). No sign-in needed. */
@RestController
public class CategoryController {

    private final CategoryService categories;

    public CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /** {@code examCount}: published exams in the category. */
    public record Category(UUID id, String name, String slug, String description, int displayOrder, int examCount) {
        static Category of(CategoryRow row) {
            return new Category(row.id(), row.name(), row.slug(), row.description(), row.displayOrder(),
                    row.publishedExamCount());
        }
    }

    @GetMapping("/api/categories")
    public List<Category> list() {
        return categories.list(true).stream().map(Category::of).toList();
    }
}
