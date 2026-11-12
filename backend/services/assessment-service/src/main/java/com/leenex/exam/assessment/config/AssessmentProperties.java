package com.leenex.exam.assessment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * assessment-service settings.
 *
 * @param catalogUrl      catalog-service inside the network (question pool and content, ADR-008)
 * @param subscriptionUrl subscription-service inside the network (access decisions, ADR-007)
 * @param serviceClient   this service's client-credentials login at identity-service (ADR-005)
 * @param timing          server-side timing rules (ADR-010)
 */
@ConfigurationProperties("assessment")
public record AssessmentProperties(
        @DefaultValue("http://localhost:8082") URI catalogUrl,
        @DefaultValue("http://localhost:8085") URI subscriptionUrl,
        @DefaultValue ServiceClient serviceClient,
        @DefaultValue Timing timing) {

    public record ServiceClient(
            @DefaultValue("http://localhost:8081/oauth2/token") URI tokenUri,
            @DefaultValue("assessment-service") String clientId,
            String clientSecret,
            @DefaultValue("3s") Duration timeout) {
    }

    /**
     * @param grace           answers and submissions this long after the end time still count (TM-2)
     * @param abandonAfter    an untimed attempt with no activity for this long is finished as abandoned (TM-5)
     * @param sweepEnabled    run the scheduled sweep (tests call it directly)
     * @param recentlySeen    questions a student saw this recently are chosen last (SG-4)
     * @param maxMinutes      the longest time-based session a student may ask for
     */
    public record Timing(
            @DefaultValue("5s") Duration grace,
            @DefaultValue("24h") Duration abandonAfter,
            @DefaultValue("true") boolean sweepEnabled,
            @DefaultValue("7d") Duration recentlySeen,
            @DefaultValue("180") int maxMinutes) {
    }
}
