package com.enterprise.iam.kernel;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holder for secret material (passwords, keys, tokens) that cannot leak by accident.
 *
 * <ul>
 *   <li>{@link #toString()} never returns the value.</li>
 *   <li>The class is intentionally not {@link java.io.Serializable} and exposes no bean getters,
 *       so JSON mappers cannot serialize the value.</li>
 *   <li>Reading the value requires an explicit {@link #reveal()} call; call sites are reviewed
 *       (Semgrep rule {@code iam-secret-reveal}).</li>
 *   <li>{@link #destroy()} zeroes the internal buffer; a destroyed secret cannot be revealed.</li>
 * </ul>
 *
 * <p>Secret values are never persisted outside Vault (ADR-0005).
 */
public final class Secret implements AutoCloseable {

    private static final String REDACTED = "Secret[REDACTED]";

    private final char[] value;
    private volatile boolean destroyed;

    private Secret(char[] value) {
        this.value = value;
    }

    /** Wraps a copy of the given characters; the caller should clear its own array afterwards. */
    public static Secret of(char[] value) {
        Objects.requireNonNull(value, "value");
        return new Secret(Arrays.copyOf(value, value.length));
    }

    /** Wraps a string value. Prefer {@link #of(char[])} where the source allows it. */
    public static Secret of(String value) {
        Objects.requireNonNull(value, "value");
        return new Secret(value.toCharArray());
    }

    /** Returns a copy of the secret value. The caller is responsible for clearing the copy. */
    public char[] reveal() {
        if (destroyed) {
            throw new IllegalStateException("Secret has been destroyed");
        }
        return Arrays.copyOf(value, value.length);
    }

    /** Independent copy that can be destroyed separately (e.g. handed to a provider while the original stays owned). */
    public Secret copy() {
        if (destroyed) {
            throw new IllegalStateException("Secret has been destroyed");
        }
        return new Secret(Arrays.copyOf(value, value.length));
    }

    /** Constant-time comparison without revealing either value to the caller. */
    public boolean matches(Secret other) {
        if (other == null || destroyed || other.destroyed) {
            return false;
        }
        // Constant-time over the longer length; no intermediate String copies are created.
        char[] a = value;
        char[] b = other.value;
        int max = Math.max(a.length, b.length);
        int diff = a.length ^ b.length;
        for (int i = 0; i < max; i++) {
            char ca = i < a.length ? a[i] : 0;
            char cb = i < b.length ? b[i] : 0;
            diff |= ca ^ cb;
        }
        return diff == 0;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    /** Overwrites the value in memory. Idempotent. */
    public void destroy() {
        Arrays.fill(value, '\0');
        destroyed = true;
    }

    @Override
    public void close() {
        destroy();
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    /** Identity-based: two secrets are never "equal" through {@code equals}; use {@link #matches}. */
    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
