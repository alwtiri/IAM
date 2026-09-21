package com.enterprise.iam.kernel;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 identifiers (RFC 9562) for all aggregates.
 * Time ordering keeps B-tree indexes compact; the random part (74 bits) prevents guessing.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(Clock.systemUTC());
    }

    public static UUID newId(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long lsb = 0x8000_0000_0000_0000L | randB;
        return new UUID(msb, lsb);
    }

    /** Extracts the embedded Unix epoch milliseconds from a UUIDv7. */
    public static long timestampMillis(UUID uuidV7) {
        if (uuidV7.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuidV7);
        }
        return uuidV7.getMostSignificantBits() >>> 16;
    }
}
