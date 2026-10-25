package com.leenex.exam.assessment.attempt;

import com.leenex.exam.assessment.config.AssessmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes mock exams whose time ran out with nobody submitting (the browser was closed), and practice sessions left
 * untouched for a day (ADR-010). Requests also finish them on the spot, so this only tidies up.
 */
@Component
public class AttemptSweep {

    private static final Logger log = LoggerFactory.getLogger(AttemptSweep.class);

    private final AttemptService attempts;
    private final boolean enabled;

    public AttemptSweep(AttemptService attempts, AssessmentProperties properties) {
        this.attempts = attempts;
        this.enabled = properties.timing().sweepEnabled();
    }

    @Scheduled(fixedDelayString = "${assessment.timing.sweep-interval:15s}", initialDelayString = "20s")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int finished = attempts.finishDue();
            if (finished > 0) {
                log.info("Finished {} attempt(s) whose time was up or that were abandoned", finished);
            }
        } catch (RuntimeException e) {
            log.warn("Attempt sweep failed: {}", e.getMessage());
        }
    }
}
