package com.leenex.exam.catalog.support;

import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small helpers shared by the controllers: paging, optimistic-lock headers, search text and field errors. */
public final class Requests {

    private static final Set<Integer> PAGE_SIZES = Set.of(10, 25, 50, 100);

    private Requests() {
    }

    public record PageMeta(int page, int size, long totalElements, int totalPages) {
        public static PageMeta of(int page, int size, long total) {
            return new PageMeta(page, size, total, (int) ((total + size - 1) / size));
        }
    }

    public record Page<T>(List<T> items, PageMeta page) {
    }

    public static void checkPaging(int page, int size, String q) {
        if (page < 0 || page > 10_000 || !PAGE_SIZES.contains(size)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "page must be 0 or more; size one of 10, 25, 50, 100.");
        }
        if (q != null && q.length() > 100) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Search text is too long.");
        }
    }

    /** A {@code LIKE} pattern matching {@code text} anywhere, with the wildcard characters escaped. */
    public static String containsPattern(String text) {
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Accepts {@code "3"}, {@code 3} or {@code W/"3"}, as browsers and tools send ETags differently. */
    public static int ifMatchVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match header with the record version is required.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        try {
            return Integer.parseInt(v.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match must be the record version.");
        }
    }

    public static ApiException staleVersion() {
        return new ApiException(ProblemCode.CONFLICT, "Someone else changed this since you opened it. Reload and try again.");
    }

    /** A 400 problem listing one or more field errors, in the same shape as bean validation failures. */
    public static ApiException invalid(List<Map<String, String>> errors) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "One or more fields are invalid.", Map.of("errors", errors));
    }

    public static ApiException invalid(String field, String message) {
        return invalid(List.of(Map.of("field", field, "message", message)));
    }
}
