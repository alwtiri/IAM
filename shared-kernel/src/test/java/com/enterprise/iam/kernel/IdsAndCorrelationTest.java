package com.enterprise.iam.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsAndCorrelationTest {

    @Test
    void generatesVersion7WithVariantAndTimestamp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
        UUID id = Ids.newId(clock);
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertEquals(clock.millis(), Ids.timestampMillis(id));
    }

    @Test
    void idsAreUniqueAndTimeOrdered() {
        UUID a = Ids.newId(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        UUID b = Ids.newId(Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC));
        assertNotEquals(a, b);
        assertTrue(a.toString().compareTo(b.toString()) < 0);
    }

    @Test
    void untrustedCorrelationIdIsReplacedWhenUnsafe() {
        assertEquals("abc-12345", CorrelationId.fromUntrusted("abc-12345").value());
        CorrelationId injected = CorrelationId.fromUntrusted("x\n{\"forged\":true}");
        assertNotEquals("x\n{\"forged\":true}", injected.value());
        assertThrows(IllegalArgumentException.class, () -> new CorrelationId("bad id with spaces"));
    }

    @Test
    void errorCodeHttpMappingIsStable() {
        assertEquals(503, ErrorCode.SECRETS_UNAVAILABLE.httpStatus());
        assertEquals(422, ErrorCode.UNSUPPORTED_CAPABILITY.httpStatus());
        assertTrue(ErrorCode.PROVIDER_UNAVAILABLE.retryableByDefault());
    }
}
