package com.enterprise.iam.kernel;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation identifier propagated UI → API → policy → provider → worker → target (spec §67).
 * Accepted from the {@code X-Correlation-Id} header only if it matches a safe format; otherwise
 * a new one is generated, so untrusted input can never inject content into logs.
 */
public record CorrelationId(String value) {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    public CorrelationId {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid correlation id format");
        }
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    /** Returns the supplied value if safe, otherwise a newly generated id. */
    public static CorrelationId fromUntrusted(String candidate) {
        if (candidate != null && SAFE.matcher(candidate).matches()) {
            return new CorrelationId(candidate);
        }
        return generate();
    }

    @Override
    public String toString() {
        return value;
    }
}
