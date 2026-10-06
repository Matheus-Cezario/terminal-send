package dev.terminalsend.client.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** RFC 9562 UUIDv7: time-ordered ids, so message ids sort roughly by creation time. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(Clock.systemUTC());
    }

    public static UUID generate(Clock clock) {
        long millis = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    public static long timestampMillis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
