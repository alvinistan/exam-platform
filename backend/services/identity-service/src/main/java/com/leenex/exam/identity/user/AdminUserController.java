package com.leenex.exam.identity.user;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User administration (contract tag Admin users). Administrators only, also enforced by the platform URL rule. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private static final Set<Integer> PAGE_SIZES = Set.of(10, 25, 50, 100);

    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    public record PageMeta(int page, int size, long totalElements, int totalPages) {
    }

    public record UserPage(List<UserView> items, PageMeta page) {
    }

    @GetMapping
    public UserPage list(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "25") int size,
                         @RequestParam(required = false) @Size(max = 100) String q,
                         @RequestParam(required = false) String role,
                         @RequestParam(required = false) String status) {
        if (page < 0 || !PAGE_SIZES.contains(size)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "page must be 0 or more; size one of 10, 25, 50, 100.");
        }
        if (q != null && q.length() > 100) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Search text is too long.");
        }
        if (role != null && !Set.of("STUDENT", "ADMIN").contains(role)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "role must be STUDENT or ADMIN.");
        }
        if (status != null && !Set.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "status must be ACTIVE or SUSPENDED.");
        }
        UserRepository.Page result = users.search(q, role, status, page, size);
        List<UserView> items = result.items().stream().map(users::view).toList();
        int totalPages = (int) ((result.total() + size - 1) / size);
        return new UserPage(items, new PageMeta(page, size, result.total(), totalPages));
    }

    @PutMapping("/{userId}/roles/ADMIN")
    public ResponseEntity<Void> grantAdmin(@PathVariable UUID userId) {
        users.grantAdmin(CurrentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/roles/ADMIN")
    public ResponseEntity<Void> revokeAdmin(@PathVariable UUID userId) {
        users.revokeAdmin(CurrentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    public record StatusChange(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
                               @NotNull @Size(min = 3, max = 500) String reason) {
    }

    @PutMapping("/{userId}/status")
    public ResponseEntity<Void> setStatus(@PathVariable UUID userId, @Valid @RequestBody StatusChange change) {
        users.setStatus(CurrentUser.id(), userId, change.status(), change.reason().strip());
        return ResponseEntity.noContent().build();
    }
}
