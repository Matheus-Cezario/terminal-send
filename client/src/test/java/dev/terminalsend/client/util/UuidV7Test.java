package dev.terminalsend.client.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariantAndEmbedsTimestamp() {
        Instant now = Instant.parse("2026-10-05T12:00:00.123Z");
        UUID id = UuidV7.generate(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(UuidV7.timestampMillis(id)).isEqualTo(now.toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID earlier = UuidV7.generate(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
        UUID later = UuidV7.generate(Clock.fixed(Instant.parse("2026-10-05T12:00:01Z"), ZoneOffset.UTC));

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }
}
