package com.enterprise.iam.kernel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretTest {

    @Test
    void toStringNeverRevealsValue() {
        Secret s = Secret.of("P@ssw0rd-very-secret");
        assertEquals("Secret[REDACTED]", s.toString());
        assertFalse(String.valueOf(s).contains("P@ss"));
    }

    @Test
    void revealReturnsCopy() {
        Secret s = Secret.of("abc");
        char[] first = s.reveal();
        first[0] = 'x';
        assertArrayEquals("abc".toCharArray(), s.reveal());
    }

    @Test
    void destroyedSecretCannotBeRevealed() {
        Secret s = Secret.of("abc");
        s.destroy();
        assertTrue(s.isDestroyed());
        assertThrows(IllegalStateException.class, s::reveal);
    }

    @Test
    void matchesComparesValuesNotIdentity() {
        assertTrue(Secret.of("same").matches(Secret.of("same")));
        assertFalse(Secret.of("same").matches(Secret.of("other")));
        assertFalse(Secret.of("same").matches(Secret.of("same-but-longer")));
        assertFalse(Secret.of("a").equals(Secret.of("a")));
    }

    @Test
    void isNotSerializable() {
        assertFalse(java.io.Serializable.class.isAssignableFrom(Secret.class));
    }
}
