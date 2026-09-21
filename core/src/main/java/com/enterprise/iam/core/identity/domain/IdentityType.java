package com.enterprise.iam.core.identity.domain;

/** Identity types (spec §5). Some types must always have an end of validity. */
public enum IdentityType {
    EMPLOYEE(false), CONTRACTOR(true), CONSULTANT(false), SERVICE(false), SYSTEM(false),
    EMERGENCY(true), TEMPORARY(true), EXTERNAL(true);

    private final boolean requiresValidUntil;

    IdentityType(boolean requiresValidUntil) {
        this.requiresValidUntil = requiresValidUntil;
    }

    public boolean requiresValidUntil() {
        return requiresValidUntil;
    }
}
