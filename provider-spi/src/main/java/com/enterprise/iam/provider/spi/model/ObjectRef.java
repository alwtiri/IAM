package com.enterprise.iam.provider.spi.model;

import java.util.Objects;

/** Generic reference used by move operations (e.g. AD object → OU). */
public record ObjectRef(String kind, String nativeId) {
    public ObjectRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nativeId, "nativeId");
    }
}
