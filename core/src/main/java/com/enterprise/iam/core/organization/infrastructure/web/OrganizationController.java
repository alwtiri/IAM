package com.enterprise.iam.core.organization.infrastructure.web;

import com.enterprise.iam.core.organization.api.CatalogEntryView;
import com.enterprise.iam.core.organization.api.OrgUnitView;
import com.enterprise.iam.core.organization.application.OrganizationService;
import com.enterprise.iam.core.organization.application.OrganizationStore.Catalog;
import com.enterprise.iam.core.organization.domain.OrgUnit;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OrganizationController {

    record CreateOrgUnitRequest(UUID parentId, @NotNull OrgUnit.Kind kind, @NotBlank @Size(max = 32) String code,
                                @NotBlank @Size(max = 200) String name) {
    }

    record RenameOrgUnitRequest(@NotBlank @Size(max = 200) String name) {
    }

    record CatalogRequest(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 200) String name, @Size(max = 200) String detail) {
    }

    private final OrganizationService org;
    private final CurrentActorProvider actors;

    OrganizationController(OrganizationService org, CurrentActorProvider actors) {
        this.org = org;
        this.actors = actors;
    }

    @GetMapping("/org-units")
    @RequiresPermission(Permissions.ORG_READ)
    PageResult<OrgUnitView> list(@RequestParam(required = false) UUID parentId, @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        return org.list(actors.require(), parentId, PageRequest.of(limit, cursor));
    }

    @PostMapping("/org-units")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ORG_WRITE)
    OrgUnitView create(@Valid @RequestBody CreateOrgUnitRequest r) {
        return org.create(actors.require(), new OrganizationService.CreateOrgUnit(r.parentId(), r.kind(), r.code(), r.name()));
    }

    @GetMapping("/org-units/{id}")
    @RequiresPermission(Permissions.ORG_READ)
    OrgUnitView get(@PathVariable UUID id) {
        return org.get(actors.require(), id);
    }

    @PatchMapping("/org-units/{id}")
    @RequiresPermission(Permissions.ORG_WRITE)
    OrgUnitView rename(@PathVariable UUID id, @RequestHeader("If-Match") long version, @Valid @RequestBody RenameOrgUnitRequest r) {
        return org.rename(actors.require(), id, r.name(), version);
    }

    @GetMapping("/positions")
    @RequiresPermission(Permissions.ORG_READ)
    List<CatalogEntryView> positions() {
        return org.listCatalog(actors.require(), Catalog.POSITION);
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ORG_WRITE)
    CatalogEntryView addPosition(@Valid @RequestBody CatalogRequest r) {
        return org.addCatalogEntry(actors.require(), Catalog.POSITION, r.code(), r.name(), r.detail());
    }

    @GetMapping("/locations")
    @RequiresPermission(Permissions.ORG_READ)
    List<CatalogEntryView> locations() {
        return org.listCatalog(actors.require(), Catalog.LOCATION);
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ORG_WRITE)
    CatalogEntryView addLocation(@Valid @RequestBody CatalogRequest r) {
        return org.addCatalogEntry(actors.require(), Catalog.LOCATION, r.code(), r.name(), r.detail());
    }
}
