package com.enterprise.iam.core.organization.api;

import java.util.Optional;
import java.util.UUID;

/** Lookups other modules need for scope resolution and validation. */
public interface OrganizationDirectory {

    /** Materialized path of an org unit, e.g. {@code /<root-id>/<child-id>/}. */
    Optional<String> orgUnitPath(UUID orgUnitId);

    boolean positionExists(UUID positionId);

    boolean locationExists(UUID locationId);
}
