package com.leenex.exam.subscription.access;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.subscription.access.AccessRules.Status;
import com.leenex.exam.subscription.support.Requests;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Free or premium status for the caller (contract tag Access). */
@RestController
public class AccessController {

    private final AccessService access;

    public AccessController(AccessService access) {
        this.access = access;
    }

    /** Public: anonymous visitors get the policy view; signed-in students also see entitlements and allowances. */
    @GetMapping("/api/access/exams")
    public Map<UUID, Status> exams(@RequestParam List<UUID> examIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(examIds));
        if (ids.isEmpty() || ids.size() > 100) {
            throw Requests.invalid("examIds", "give between 1 and 100 exam IDs");
        }
        return access.statuses(ids, Requests.optionalUserId());
    }

    @GetMapping("/api/access/me")
    public AccessService.MyAccess me() {
        return access.me(CurrentUser.id());
    }
}
