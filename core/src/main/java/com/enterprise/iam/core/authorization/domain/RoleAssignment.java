package com.enterprise.iam.core.authorization.domain;

import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A time-bound, scoped grant of a role to an identity (spec §19). */
public record RoleAssignment(UUID id, UUID identityId, UUID roleId, AssignmentScope scope, Source source, Status status,
                             Instant validFrom, Instant validUntil, UUID grantedBy, Instant grantedAt, UUID revokedBy,
                             Instant revokedAt, String reason, long version) {

    public enum Source { DIRECT, BOOTSTRAP, REQUEST, JML, EMERGENCY }

    public enum Status { ACTIVE, REVOKED, EXPIRED }

    public RoleAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw IamException.validation("validUntil", "BEFORE_START", "validUntil must be after validFrom");
        }
        if (reason != null && reason.length() > 500) {
            throw IamException.validation("reason", "TOO_LONG", "max 500 characters");
        }
    }

    public static RoleAssignment grant(UUID id, UUID identityId, UUID roleId, AssignmentScope scope, Source source,
                                       Instant validFrom, Instant validUntil, UUID grantedBy, Instant now, String reason) {
        Instant from = validFrom == null ? now : validFrom;
        if (validUntil != null && !validUntil.isAfter(now)) {
            throw IamException.validation("validUntil", "IN_PAST", "validUntil must be in the future");
        }
        return new RoleAssignment(id, identityId, roleId, scope, source, Status.ACTIVE, from, validUntil, grantedBy, now,
                null, null, reason, 0);
    }

    /** Effective = ACTIVE and inside the validity window at {@code now}. */
    public boolean isEffective(Instant now) {
        return status == Status.ACTIVE && !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
    }

    public RoleAssignment revoke(UUID by, Instant now, String why) {
        if (status != Status.ACTIVE) {
            throw IamException.invalidTransition("Role assignment", status, Status.REVOKED);
        }
        return new RoleAssignment(id, identityId, roleId, scope, source, Status.REVOKED, validFrom, validUntil, grantedBy,
                grantedAt, by, now, why, version);
    }

    public RoleAssignment expire(Instant now) {
        if (status != Status.ACTIVE) {
            throw IamException.invalidTransition("Role assignment", status, Status.EXPIRED);
        }
        return new RoleAssignment(id, identityId, roleId, scope, source, Status.EXPIRED, validFrom, validUntil, grantedBy,
                grantedAt, null, now, "validity expired", version);
    }
}
