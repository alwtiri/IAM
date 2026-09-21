package com.enterprise.iam.core.organization.application;

import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.organization.application.OrganizationStore.Catalog;
import com.enterprise.iam.core.organization.domain.OrgUnit;
import java.util.Optional;
import java.util.UUID;

/** Store-backed lookups for other modules; deliberately free of authorization to avoid bean cycles with the guard. */
public class OrganizationDirectoryService implements OrganizationDirectory {

    private final OrganizationStore store;

    public OrganizationDirectoryService(OrganizationStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> orgUnitPath(UUID orgUnitId) {
        return orgUnitId == null ? Optional.empty() : store.find(orgUnitId).map(OrgUnit::path);
    }

    @Override
    public boolean positionExists(UUID positionId) {
        return store.catalogEntryExists(Catalog.POSITION, positionId);
    }

    @Override
    public boolean locationExists(UUID locationId) {
        return store.catalogEntryExists(Catalog.LOCATION, locationId);
    }
}
