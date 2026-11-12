package com.leenex.exam.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EndpointSecurityMatrixTest {

    @Test
    void fillsPathVariablesIncludingOnesWithRegexBraces() {
        assertThat(EndpointSecurityMatrix.fill("/api/media/{key:[a-f0-9]{32}\\.(?:png|jpg)}", Map.of("key", "k.png")))
                .isEqualTo("/api/media/k.png");
        assertThat(EndpointSecurityMatrix.fill("/api/attempts/{attemptId}/answers/{position}", Map.of("position", "1")))
                .matches("/api/attempts/[0-9a-f-]{36}/answers/1");
    }
}
