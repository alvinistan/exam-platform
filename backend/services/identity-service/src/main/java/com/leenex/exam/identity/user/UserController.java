package com.leenex.exam.identity.user;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile (contract tag Users). */
@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<UserView> me() {
        UserView me = users.get(CurrentUser.id());
        return ResponseEntity.ok().eTag(Integer.toString(me.version())).body(me);
    }

    public record ProfileUpdate(@Size(min = 1, max = 120) String displayName, Map<String, Object> preferences) {
    }

    @PatchMapping
    public ResponseEntity<UserView> update(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody ProfileUpdate update) {
        UserView me = users.updateProfile(CurrentUser.id(), parseVersion(ifMatch), update.displayName(), update.preferences());
        return ResponseEntity.ok().eTag(Integer.toString(me.version())).body(me);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        users.deleteOwnAccount(CurrentUser.id());
        return ResponseEntity.accepted().build();
    }

    /** Accepts {@code "3"}, {@code 3} or {@code W/"3"}, as browsers and tools send ETags differently. */
    static int parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match header with the profile version is required.");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match must be the profile version.");
        }
    }
}
