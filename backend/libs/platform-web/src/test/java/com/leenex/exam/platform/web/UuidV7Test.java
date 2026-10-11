package com.leenex.exam.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesTheTimestamp() {
        Instant now = Instant.parse("2026-10-11T10:15:30.123Z");
        UUID id = UuidV7.generate(Clock.fixed(now, ZoneOffset.UTC));
        assertThat(UuidV7.timestampMillis(id)).isEqualTo(now.toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID earlier = UuidV7.generate(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        UUID later = UuidV7.generate(Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC));
        // Lexical order of the canonical string is the database's uuid order.
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void isUniqueAcrossManyCalls() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(UuidV7.generate());
        }
        assertThat(seen).hasSize(100_000);
    }

    @Test
    void rejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
