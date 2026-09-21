package com.enterprise.iam.core.organization.application;

import com.enterprise.iam.core.organization.domain.CatalogEntry;
import com.enterprise.iam.core.organization.domain.OrgUnit;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationStore {

    enum Catalog { POSITION, LOCATION }

    void insert(OrgUnit unit);

    /** @return false if {@code expectedVersion} no longer matches (optimistic lock) */
    boolean update(OrgUnit unit, long expectedVersion);

    Optional<OrgUnit> find(UUID id);

    boolean codeExists(UUID organizationId, String code);

    List<OrgUnit> list(ScopeFilter filter, UUID parentId, PageRequest page);

    void insert(Catalog catalog, CatalogEntry entry);

    boolean catalogCodeExists(Catalog catalog, String code);

    List<CatalogEntry> listCatalog(Catalog catalog);

    boolean catalogEntryExists(Catalog catalog, UUID id);
}
