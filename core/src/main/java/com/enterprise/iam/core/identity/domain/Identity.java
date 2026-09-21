package com.enterprise.iam.core.identity.domain;

import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Security principal of the platform (spec §6). State changes only through {@link #transitionTo}. */
public record Identity(UUID id, UUID personId, IdentityType type, String username, IdentityState state, String stateReason,
                       Instant validFrom, Instant validUntil, long version) {

    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

    public Identity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(personId, "personId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(validFrom, "validFrom");
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw IamException.validation("username", "INVALID", "3-64 characters: lower-case letters, digits, '.', '_' or '-'");
        }
        if (type.requiresValidUntil() && validUntil == null) {
            throw IamException.validation("validUntil", "REQUIRED", type + " identities require an end of validity");
        }
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw IamException.validation("validUntil", "BEFORE_START", "validUntil must be after validFrom");
        }
    }

    public static Identity create(UUID id, UUID personId, IdentityType type, String username, Instant now, Instant validUntil) {
        return new Identity(id, personId, type, username, IdentityState.PENDING, null, now, validUntil, 0);
    }

    public Identity transitionTo(IdentityState next, String reason) {
        if (!state.canTransitionTo(next)) {
            throw IamException.invalidTransition("Identity", state, next);
        }
        if ((next == IdentityState.SUSPENDED || next == IdentityState.DISABLED) && (reason == null || reason.isBlank())) {
            throw IamException.validation("reason", "REQUIRED", "a reason is required to " + next.name().toLowerCase());
        }
        if (reason != null && reason.length() > 500) {
            throw IamException.validation("reason", "TOO_LONG", "max 500 characters");
        }
        return new Identity(id, personId, type, username, next, reason, validFrom, validUntil, version);
    }

    public boolean isExpired(Instant now) {
        return validUntil != null && !now.isBefore(validUntil);
    }
}
