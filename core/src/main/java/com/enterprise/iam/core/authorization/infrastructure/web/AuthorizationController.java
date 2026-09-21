package com.enterprise.iam.core.authorization.infrastructure.web;

import com.enterprise.iam.core.authorization.api.EffectiveAccessView;
import com.enterprise.iam.core.authorization.api.RoleAssignmentView;
import com.enterprise.iam.core.authorization.api.RoleView;
import com.enterprise.iam.core.authorization.application.RoleAssignmentService;
import com.enterprise.iam.core.authorization.domain.ScopeElement;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AuthenticatedEndpoint;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthorizationController {

    record ScopeElementRequest(@NotNull ScopeElement.Type type, @NotBlank @Size(max = 64) String value) {
    }

    record GrantRequest(@NotNull UUID identityId, @NotNull UUID roleId, @NotEmpty @Size(max = 50) List<@Valid ScopeElementRequest> scope,
                        Instant validFrom, Instant validUntil, @Size(max = 500) String reason) {
    }

    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final RoleAssignmentService service;
    private final CurrentActorProvider actors;

    AuthorizationController(RoleAssignmentService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/me")
    @AuthenticatedEndpoint
    EffectiveAccessView me() {
        return service.me(actors.require());
    }

    @GetMapping("/permissions")
    @RequiresPermission(Permissions.ROLE_READ)
    List<String> permissions() {
        return service.permissions(actors.require());
    }

    @GetMapping("/roles")
    @RequiresPermission(Permissions.ROLE_READ)
    List<RoleView> roles() {
        return service.roles(actors.require());
    }

    @GetMapping("/roles/{id}")
    @RequiresPermission(Permissions.ROLE_READ)
    RoleView role(@PathVariable UUID id) {
        return service.role(actors.require(), id);
    }

    @GetMapping("/role-assignments")
    @RequiresPermission(Permissions.ROLE_ASSIGNMENT_READ)
    PageResult<RoleAssignmentView> assignments(@RequestParam UUID identityId, @RequestParam(defaultValue = "false") boolean activeOnly,
                                               @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return service.listFor(actors.require(), identityId, activeOnly, PageRequest.of(limit, cursor));
    }

    @PostMapping("/role-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ROLE_ASSIGNMENT_WRITE)
    @RequiresStepUp
    RoleAssignmentView grant(@Valid @RequestBody GrantRequest r) {
        Set<ScopeElement> scope = r.scope().stream().map(s -> new ScopeElement(s.type(), s.value())).collect(Collectors.toSet());
        return service.grant(actors.require(), new RoleAssignmentService.GrantCommand(r.identityId(), r.roleId(), scope,
                r.validFrom(), r.validUntil(), r.reason()));
    }

    @PostMapping("/role-assignments/{id}:revoke")
    @RequiresPermission(Permissions.ROLE_ASSIGNMENT_WRITE)
    @RequiresStepUp
    RoleAssignmentView revoke(@PathVariable UUID id, @Valid @RequestBody RevokeRequest r) {
        return service.revoke(actors.require(), id, r.reason());
    }
}
