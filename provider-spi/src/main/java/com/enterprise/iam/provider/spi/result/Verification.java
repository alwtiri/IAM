package com.enterprise.iam.provider.spi.result;

import com.enterprise.iam.provider.spi.VerificationMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Evidence that a mutating operation's effect was confirmed on the target (spec §46).
 *
 * @param mode       how it was verified; {@link VerificationMode#NOT_POSSIBLE} is not a verification
 * @param verifiedAt when the confirmation was obtained
 * @param summary    short, non-sensitive description of what was observed (never secret values)
 */
public record Verification(VerificationMode mode, Instant verifiedAt, String summary) {

    public Verification {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(summary, "summary");
        if (mode == VerificationMode.NOT_POSSIBLE) {
            throw new IllegalArgumentException("NOT_POSSIBLE cannot be used as a verification; report UNKNOWN instead");
        }
        if (summary.isBlank() || summary.length() > 512) {
            throw new IllegalArgumentException("summary must be 1..512 characters");
        }
    }
}
