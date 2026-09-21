package com.enterprise.iam.provider.spi.model;

import java.util.Objects;

/** One difference between desired and actual state (spec §45). */
public record Drift(Kind kind, String objectRef, String expected, String actual) {

    public enum Kind {
        UNAUTHORIZED_ACCOUNT, MISSING_ACCOUNT, STATUS_MISMATCH, UNEXPECTED_GROUP_MEMBERSHIP,
        MISSING_GROUP_MEMBERSHIP, PRIVILEGE_CHANGE, CONFIGURATION_DRIFT, EXPIRED_CREDENTIAL
    }

    public Drift {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectRef, "objectRef");
    }
}
