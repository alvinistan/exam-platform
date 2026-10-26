package com.leenex.exam.subscription.access;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.subscription.access.PolicyRepository.PolicyRow;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository;
import com.leenex.exam.subscription.catalogfeed.ExamViewRepository.ExamView;
import com.leenex.exam.subscription.support.AuditLog;
import com.leenex.exam.subscription.support.Requests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Free-access policies with a live preview of which exams they make free (contract tag Admin billing). */
@RestController
@RequestMapping("/api/admin/billing/policies")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPolicyController {

    private static final Set<String> RULES = Set.of("FA1_CATALOGUE_POSITION", "FA2_WELCOME_ALLOWANCE", "FA4_FREE_FEATURES");

    private final PolicyRepository policies;
    private final ExamViewRepository exams;
    private final AuditLog audit;
    private final JsonMapper json;

    public AdminPolicyController(PolicyRepository policies, ExamViewRepository exams, AuditLog audit, JsonMapper json) {
        this.policies = policies;
        this.exams = exams;
        this.audit = audit;
        this.json = json;
    }

    public record PolicyView(String ruleCode, boolean active, JsonNode params, int version,
                             java.time.OffsetDateTime updatedAt) {
        static PolicyView of(PolicyRow r) {
            return new PolicyView(r.ruleCode(), r.active(), r.params(), r.version(), r.updatedAt());
        }
    }

    /** One published exam and what the current policy makes of it, in catalogue order. */
    public record ExamAccess(UUID examId, String title, String slug, UUID categoryId, Integer displayOrder,
                             String access, String reason) {
    }

    public record PoliciesView(List<PolicyView> policies, List<UUID> freeExamIds, List<ExamAccess> exams) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PoliciesView list() {
        FreePolicy policy = policies.current();
        List<ExamView> published = exams.publishedInOrder();
        Map<UUID, String> free = AccessRules.freeByPosition(policy, published);
        List<ExamAccess> rows = new ArrayList<>();
        for (ExamView e : published) {
            String reason = free.get(e.examId());
            rows.add(new ExamAccess(e.examId(), e.title(), e.slug(), e.categoryId(), e.displayOrder(),
                    reason == null ? "PREMIUM" : "FREE", reason == null ? "SUBSCRIPTION_REQUIRED" : reason));
        }
        return new PoliciesView(policies.all().stream().map(PolicyView::of).toList(), List.copyOf(free.keySet()), rows);
    }

    public record PolicyInput(@NotNull Boolean active, @NotNull JsonNode params) {
    }

    /** Applies from the next request; attempts already started keep going (FA-6). */
    @PutMapping("/{ruleCode}")
    @Transactional
    public ResponseEntity<PolicyView> update(@PathVariable String ruleCode,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @Valid @RequestBody PolicyInput in) {
        if (!RULES.contains(ruleCode)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "No such policy.");
        }
        int expected = Requests.ifMatchVersion(ifMatch);
        PolicyRow before = policies.lock(ruleCode).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "No such policy."));
        if (before.version() != expected) {
            throw Requests.staleVersion();
        }
        JsonNode params = normalise(ruleCode, in.params());
        policies.update(ruleCode, in.active(), params, CurrentUser.id());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("from", Map.of("active", before.active(), "params", before.params()));
        summary.put("to", Map.of("active", in.active(), "params", params));
        audit.record(CurrentUser.id(), "POLICY_UPDATED", "POLICY", ruleCode, summary);
        PolicyRow after = policies.all().stream().filter(r -> r.ruleCode().equals(ruleCode)).findFirst().orElseThrow();
        return ResponseEntity.ok().eTag(Integer.toString(after.version())).body(PolicyView.of(after));
    }

    /** Validates a rule's parameters and keeps only the known ones. */
    private JsonNode normalise(String ruleCode, JsonNode params) {
        ObjectNode out = json.createObjectNode();
        switch (ruleCode) {
            case "FA1_CATALOGUE_POSITION" -> {
                JsonNode count = params.get("count");
                if (count == null || !count.isInt() || count.asInt() < 0 || count.asInt() > 1000) {
                    throw Requests.invalid("params.count", "use a whole number from 0 to 1000");
                }
                String scope = params.path("scope").asString("GLOBAL");
                if (!Set.of("GLOBAL", "CATEGORY").contains(scope)) {
                    throw Requests.invalid("params.scope", "use GLOBAL or CATEGORY");
                }
                out.put("count", count.asInt());
                out.put("scope", scope);
                out.put("orderBy", "DISPLAY_ORDER");
            }
            case "FA2_WELCOME_ALLOWANCE" -> {
                JsonNode attempts = params.get("attempts");
                if (attempts == null || !attempts.isInt() || attempts.asInt() < 1 || attempts.asInt() > 50) {
                    throw Requests.invalid("params.attempts", "use a whole number from 1 to 50");
                }
                out.put("attempts", attempts.asInt());
            }
            case "FA4_FREE_FEATURES" -> {
                JsonNode modes = params.get("modes");
                List<String> chosen = new ArrayList<>();
                if (modes != null && modes.isArray()) {
                    for (JsonNode m : modes) {
                        if (!FreePolicy.ALL_MODES.contains(m.asString())) {
                            throw Requests.invalid("params.modes", "use PRACTICE, MOCK or USER_FRIENDLY");
                        }
                        if (!chosen.contains(m.asString())) {
                            chosen.add(m.asString());
                        }
                    }
                }
                if (chosen.isEmpty()) {
                    throw Requests.invalid("params.modes", "choose at least one mode");
                }
                var array = out.putArray("modes");
                FreePolicy.ALL_MODES.stream().filter(chosen::contains).forEach(array::add);
            }
            default -> throw new IllegalArgumentException(ruleCode);
        }
        return out;
    }
}
