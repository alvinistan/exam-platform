package com.leenex.exam.subscription.billing;

import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payment results arriving from PayHere's servers, and the labelled test stand-in. */
@RestController
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final PaymentService service;
    private final Payments payments;

    public PaymentWebhookController(PaymentService service, Payments payments) {
        this.service = service;
        this.payments = payments;
    }

    /**
     * PayHere's notify_url (public; authenticity comes from md5sig). Answers 200 to every genuine message, even a
     * repeat, so PayHere stops retrying; 400 when the signature is wrong.
     */
    @PostMapping(value = "/api/payments/webhooks/payhere", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> payhere(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> p = new TreeMap<>(form.toSingleValueMap());
        Notification n = service.verifyPayHere(p);
        if (n == null) {
            log.warn("Rejected a PayHere notification with a missing or wrong signature (order {})", p.get("order_id"));
            return ResponseEntity.badRequest().body("invalid signature");
        }
        String raw = p.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
        return ResponseEntity.ok(service.apply(n, PaymentService.bytes(raw)));
    }

    public record TestOutcome(@NotNull @Pattern(regexp = "SUCCEEDED|FAILED|CANCELLED") String outcome) {
    }

    /**
     * TEST provider only: the student's own choice on our labelled test page stands in for the provider's message.
     * Refused when real payments are configured.
     */
    @PostMapping("/api/payments/test/{transactionId}")
    public ResponseEntity<Map<String, String>> test(@PathVariable UUID transactionId, @Valid @RequestBody TestOutcome in) {
        if (!"TEST".equals(service.provider())) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Not found.");
        }
        Payments.Transaction tx = payments.transaction(transactionId)
                .filter(t -> t.studentId().equals(CurrentUser.id()) && "TEST".equals(t.provider()))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Payment not found."));
        Notification n = new Notification("TEST", "test:" + tx.id() + ":" + in.outcome(), tx.id(),
                Notification.Outcome.valueOf(in.outcome()), "TEST-" + tx.id(), tx.amount(), tx.currency(), "Test payment");
        return ResponseEntity.ok(Map.of("result", service.apply(n, PaymentService.bytes(n.eventId()))));
    }
}
