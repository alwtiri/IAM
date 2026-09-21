package com.enterprise.iam.core.identity.infrastructure.web;

import com.enterprise.iam.core.identity.api.IdentityView;
import com.enterprise.iam.core.identity.api.PersonView;
import com.enterprise.iam.core.identity.application.IdentityService;
import com.enterprise.iam.core.identity.application.PersonService;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class IdentityController {

    record PersonRequest(UUID orgUnitId, @Size(max = 32) String employeeId, @NotBlank @Size(max = 100) String givenName,
                         @NotBlank @Size(max = 100) String familyName, @Size(max = 200) String displayName, UUID positionId,
                         UUID locationId, UUID managerPersonId, Person.EmploymentStatus employmentStatus, LocalDate startDate,
                         LocalDate endDate, @Size(max = 320) String email, @Size(max = 40) String phone) {
        PersonService.PersonData data() {
            return new PersonService.PersonData(orgUnitId, employeeId, givenName, familyName, displayName, positionId, locationId,
                    managerPersonId, employmentStatus, startDate, endDate, email, phone);
        }
    }

    record CreateIdentityRequest(@NotNull UUID personId, @NotNull IdentityType type, @NotBlank @Size(max = 64) String username,
                                 Instant validUntil) {
    }

    record ReasonRequest(@Size(max = 500) String reason) {
    }

    record PlatformUserRequest(@NotBlank @Size(max = 255) String subject) {
    }

    private final PersonService persons;
    private final IdentityService identities;
    private final CurrentActorProvider actors;

    IdentityController(PersonService persons, IdentityService identities, CurrentActorProvider actors) {
        this.persons = persons;
        this.identities = identities;
        this.actors = actors;
    }

    // ---- persons

    @GetMapping("/persons")
    @RequiresPermission(Permissions.PERSON_READ)
    PageResult<PersonView> listPersons(@RequestParam(required = false) String q, @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        return persons.list(actors.require(), q, PageRequest.of(limit, cursor));
    }

    @PostMapping("/persons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.PERSON_WRITE)
    PersonView createPerson(@Valid @RequestBody PersonRequest r) {
        return persons.create(actors.require(), r.data());
    }

    @GetMapping("/persons/{id}")
    @RequiresPermission(Permissions.PERSON_READ)
    PersonView getPerson(@PathVariable UUID id) {
        return persons.get(actors.require(), id);
    }

    @PatchMapping("/persons/{id}")
    @RequiresPermission(Permissions.PERSON_WRITE)
    PersonView updatePerson(@PathVariable UUID id, @RequestHeader("If-Match") long version, @Valid @RequestBody PersonRequest r) {
        return persons.update(actors.require(), id, r.data(), version);
    }

    // ---- identities

    @GetMapping("/identities")
    @RequiresPermission(Permissions.IDENTITY_READ)
    PageResult<IdentityView> listIdentities(@RequestParam(required = false) UUID personId, @RequestParam(required = false) IdentityState state,
                                            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return identities.list(actors.require(), personId, state == null ? null : state.name(), PageRequest.of(limit, cursor));
    }

    @PostMapping("/identities")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.IDENTITY_WRITE)
    IdentityView createIdentity(@Valid @RequestBody CreateIdentityRequest r) {
        return identities.create(actors.require(), r.personId(), r.type(), r.username(), r.validUntil());
    }

    @GetMapping("/identities/{id}")
    @RequiresPermission(Permissions.IDENTITY_READ)
    IdentityView getIdentity(@PathVariable UUID id) {
        return identities.get(actors.require(), id);
    }

    @PostMapping("/identities/{id}:activate")
    @RequiresPermission(Permissions.IDENTITY_LIFECYCLE)
    IdentityView activate(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return identities.transition(actors.require(), id, IdentityState.ACTIVE, r == null ? null : r.reason());
    }

    @PostMapping("/identities/{id}:suspend")
    @RequiresPermission(Permissions.IDENTITY_LIFECYCLE)
    IdentityView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return identities.transition(actors.require(), id, IdentityState.SUSPENDED, r.reason());
    }

    @PostMapping("/identities/{id}:reinstate")
    @RequiresPermission(Permissions.IDENTITY_LIFECYCLE)
    IdentityView reinstate(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return identities.transition(actors.require(), id, IdentityState.ACTIVE, r == null ? null : r.reason());
    }

    @PostMapping("/identities/{id}:disable")
    @RequiresPermission(Permissions.IDENTITY_LIFECYCLE)
    @RequiresStepUp
    IdentityView disable(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return identities.transition(actors.require(), id, IdentityState.DISABLED, r.reason());
    }

    @PutMapping("/identities/{id}/platform-user")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.IDENTITY_PLATFORM_USER)
    @RequiresStepUp
    void linkPlatformUser(@PathVariable UUID id, @Valid @RequestBody PlatformUserRequest r) {
        identities.linkPlatformUser(actors.require(), id, r.subject());
    }
}
