package com.enterprise.iam.core.target.infrastructure.web;

import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.target.api.TargetView;
import com.enterprise.iam.core.target.application.TargetService;
import com.enterprise.iam.core.target.domain.Target;
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
@RequestMapping("/api/v1/targets")
class TargetController {

    record TargetRequest(@NotBlank @Size(max = 200) String name, @Size(max = 253) String hostname, @Size(max = 45) String ipAddress,
                         @Size(max = 253) String dnsName, @NotNull Target.Type type, @Size(max = 100) String platform,
                         @Size(max = 100) String operatingSystem, @NotBlank String environment, Target.Criticality criticality,
                         Target.Classification classification, @NotNull UUID ownerOrgUnitId, UUID ownerIdentityId,
                         UUID technicalOwnerIdentityId, UUID businessOwnerIdentityId, UUID locationId,
                         @Size(max = 50) List<String> tags, Target.Status status) {
        TargetService.TargetData data() {
            return new TargetService.TargetData(name, hostname, ipAddress, dnsName, type, platform, operatingSystem, environment,
                    criticality, classification, ownerOrgUnitId, ownerIdentityId, technicalOwnerIdentityId, businessOwnerIdentityId,
                    locationId, tags, status);
        }
    }

    private final TargetService targets;
    private final CurrentActorProvider actors;

    TargetController(TargetService targets, CurrentActorProvider actors) {
        this.targets = targets;
        this.actors = actors;
    }

    @GetMapping
    @RequiresPermission(Permissions.TARGET_READ)
    PageResult<TargetView> list(@RequestParam(required = false) Target.Type type, @RequestParam(required = false) String environment,
                                @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return targets.list(actors.require(), type == null ? null : type.name(), environment, PageRequest.of(limit, cursor));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.TARGET_WRITE)
    TargetView create(@Valid @RequestBody TargetRequest r) {
        return targets.create(actors.require(), r.data());
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.TARGET_READ)
    TargetView get(@PathVariable UUID id) {
        return targets.get(actors.require(), id);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permissions.TARGET_WRITE)
    TargetView update(@PathVariable UUID id, @RequestHeader("If-Match") long version, @Valid @RequestBody TargetRequest r) {
        return targets.update(actors.require(), id, r.data(), version);
    }
}
