package com.huuhv.socialanalytics.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UtcDateTimesTest {

    @Test
    void toUtcConvertsInstantFromAnyOffsetToUtcWallClock() {
        Instant fromVietnam = OffsetDateTime.parse("2026-01-01T19:00:00+07:00").toInstant();

        assertThat(UtcDateTimes.toUtc(fromVietnam)).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));
    }

    @Test
    void toInstantTreatsStoredValueAsUtc() {
        assertThat(UtcDateTimes.toInstant(LocalDateTime.of(2026, 1, 1, 12, 0)))
                .isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
    }

    @Test
    void roundTripTruncatesToMicrosecondsLikeDatetime6() {
        Instant withNanos = Instant.parse("2026-01-01T12:00:00.123456789Z");

        assertThat(UtcDateTimes.toInstant(UtcDateTimes.toUtc(withNanos)))
                .isEqualTo(Instant.parse("2026-01-01T12:00:00.123456Z"));
    }

    @Test
    void nullStaysNull() {
        assertThat(UtcDateTimes.toUtc(null)).isNull();
        assertThat(UtcDateTimes.toInstant(null)).isNull();
    }

    @Test
    void nowIsUtcRegardlessOfJvmTimeZone() {
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(Duration.between(expected, UtcDateTimes.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
