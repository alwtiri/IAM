package com.enterprise.iam.core.shared.api.security;

import java.util.UUID;

/**
 * Scope-relevant attributes of a protected object. Absent attributes are {@code null}; a scope constraint on an
 * absent attribute never matches (fail closed).
 *
 * @param orgUnitPath        materialized path of the owning org unit, e.g. {@code /<uuid>/<uuid>/}
 * @param environment        e.g. PRODUCTION, TEST
 * @param providerType       e.g. linux, ad
 * @param providerInstanceId provider instance
 * @param targetId           target
 */
public record ResourceScope(String orgUnitPath, String environment, String providerType, UUID providerInstanceId, UUID targetId) {

    /** For platform-level objects (catalogs, system info) that only GLOBAL scope covers. */
    public static final ResourceScope PLATFORM = new ResourceScope(null, null, null, null, null);

    public static ResourceScope orgUnit(String orgUnitPath) {
        return new ResourceScope(orgUnitPath, null, null, null, null);
    }

    public static ResourceScope target(UUID targetId, String environment, String orgUnitPath) {
        return new ResourceScope(orgUnitPath, environment, null, null, targetId);
    }
}
