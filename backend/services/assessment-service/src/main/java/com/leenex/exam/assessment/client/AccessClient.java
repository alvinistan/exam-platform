package com.leenex.exam.assessment.client;

import com.leenex.exam.assessment.config.AssessmentProperties;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** subscription-service's access decisions (ADR-007). Every start asks; nothing is cached. */
@Component
public class AccessClient {

    public record Decision(boolean allowed, String bank, String reason, Integer attemptsLeft) {
    }

    private final InternalHttp http;
    private final JsonMapper json;
    private final URI uri;

    public AccessClient(InternalHttp http, JsonMapper json, AssessmentProperties properties) {
        this.http = http;
        this.json = json;
        this.uri = URI.create(properties.subscriptionUrl().toString().replaceAll("/+$", "") + "/internal/access-decisions");
    }

    /**
     * Records the start of {@code attemptId} against the student's free attempts (when it isn't Premium) and returns
     * the decision. Asking again for the same attempt is safe: it is counted once.
     */
    public Decision consume(UUID studentId, UUID examId, String mode, UUID attemptId) {
        try {
            return json.treeToValue(http.post(uri, Map.of("studentId", studentId, "examId", examId, "mode", mode,
                    "consume", true, "attemptId", attemptId)), Decision.class);
        } catch (InternalHttp.CallFailed e) {
            throw InternalHttp.unavailable();
        }
    }

    /** The problem a student sees when a start is refused. */
    public static ApiException refusal(Decision decision, String modeLabel) {
        return switch (decision.reason()) {
            case "FREE_ATTEMPTS_USED" -> new ApiException(ProblemCode.FREE_ALLOWANCE_USED,
                    "You've used your free " + modeLabel + " attempts for this exam. A plan gives you more.",
                    Map.of("reason", decision.reason()));
            case "PLAN_ATTEMPTS_USED" -> new ApiException(ProblemCode.FREE_ALLOWANCE_USED,
                    "You've used all the " + modeLabel + " attempts in your plan for this exam.",
                    Map.of("reason", decision.reason()));
            case "MODE_NOT_AVAILABLE" -> new ApiException(ProblemCode.MODE_DISABLED,
                    modeLabel + " isn't available for this exam.");
            case "EXAM_UNAVAILABLE" -> new ApiException(ProblemCode.EXAM_UNAVAILABLE,
                    "This exam isn't available at the moment.");
            default -> new ApiException(ProblemCode.SUBSCRIPTION_REQUIRED,
                    modeLabel + " on this exam needs a plan.", Map.of("reason", decision.reason()));
        };
    }
}
