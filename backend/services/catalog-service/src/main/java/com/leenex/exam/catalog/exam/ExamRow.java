package com.leenex.exam.catalog.exam;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of {@code catalog.exam}. */
public record ExamRow(UUID id, UUID categoryId, String title, String slug, String shortDescription, String instructions,
                      String seoTitle, String seoDescription, Integer displayOrder, boolean featured, String status,
                      OffsetDateTime publishedAt, OffsetDateTime updatedAt, int version) {

    public boolean published() {
        return "PUBLISHED".equals(status);
    }
}
