package com.enterprise.iam.provider.spi.model;

import java.util.Objects;

/**
 * Reference to an account on a target.
 *
 * @param nativeId stable target-side identifier (UID/SID/objectGUID/username as appropriate); may be null before creation
 * @param name     login name
 */
public record AccountRef(String nativeId, String name) {
    public AccountRef {
        Objects.requireNonNull(name, "name");
    }
}
