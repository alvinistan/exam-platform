package com.leenex.exam.subscription.billing;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payments for the administrator: newest first, optionally for one student. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

    private final Payments payments;

    public AdminPaymentController(Payments payments) {
        this.payments = payments;
    }

    @GetMapping("/api/admin/billing/payments")
    public List<Payments.Transaction> list(@RequestParam(required = false) UUID studentId) {
        return payments.transactions(studentId, 200);
    }
}
