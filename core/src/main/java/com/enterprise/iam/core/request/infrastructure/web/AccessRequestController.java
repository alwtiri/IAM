package com.enterprise.iam.core.request.infrastructure.web;

import com.enterprise.iam.core.request.api.AccessRequestView;
import com.enterprise.iam.core.request.api.RequestableRole;
import com.enterprise.iam.core.request.application.AccessRequestService;
import com.enterprise.iam.core.shared.api.security.AuthenticatedEndpoint;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service access requests and approvals. Every signed-in identity may request access and see its own requests;
 * approvers see what waits for them; oversight of all requests needs {@code request:read} (checked in the service).
 */
@RestController
@RequestMapping("/api/v1")
class AccessRequestController {

    record SubmitRequest(@NotNull UUID roleId, @Size(max = 16) String scopeType, @Size(max = 1000) String justification,
                         @Min(1) @Max(3650) int durationDays) {
    }

    record DecisionRequest(@Size(max = 1000) String comment) {
    }

    private final AccessRequestService requests;
    private final CurrentActorProvider actors;

    AccessRequestController(AccessRequestService requests, CurrentActorProvider actors) {
        this.requests = requests;
        this.actors = actors;
    }

    @GetMapping("/access-requests/requestable-roles")
    @AuthenticatedEndpoint
    List<RequestableRole> requestableRoles() {
        return requests.requestableRoles(actors.require());
    }

    @PostMapping("/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @AuthenticatedEndpoint
    AccessRequestView submit(@Valid @RequestBody SubmitRequest r) {
        return requests.submit(actors.require(), new AccessRequestService.Submit(r.roleId(), r.scopeType(), r.justification(), r.durationDays()));
    }

    @GetMapping("/access-requests")
    @AuthenticatedEndpoint
    List<AccessRequestView> list(@RequestParam(defaultValue = "true") boolean mine, @RequestParam(required = false) String status) {
        return mine ? requests.mine(actors.require()) : requests.all(actors.require(), status);
    }

    @GetMapping("/access-requests/{id}")
    @AuthenticatedEndpoint
    AccessRequestView get(@PathVariable UUID id) {
        return requests.get(actors.require(), id);
    }

    @PostMapping("/access-requests/{id}:cancel")
    @AuthenticatedEndpoint
    AccessRequestView cancel(@PathVariable UUID id) {
        return requests.cancel(actors.require(), id);
    }

    @GetMapping("/approvals")
    @AuthenticatedEndpoint
    List<AccessRequestView> approvals() {
        return requests.pendingApprovals(actors.require());
    }

    @PostMapping("/access-requests/{id}:approve")
    @AuthenticatedEndpoint
    @RequiresStepUp
    AccessRequestView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return requests.decide(actors.require(), id, true, r == null ? null : r.comment());
    }

    @PostMapping("/access-requests/{id}:reject")
    @AuthenticatedEndpoint
    AccessRequestView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return requests.decide(actors.require(), id, false, r.comment());
    }
}
