package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.PersonView;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Person use cases (spec §6, F2). A person's scope is its org unit; persons without one are GLOBAL-only. */
public class PersonService {

    public record PersonData(UUID orgUnitId, String employeeId, String givenName, String familyName, String displayName,
                             UUID positionId, UUID locationId, UUID managerPersonId, Person.EmploymentStatus employmentStatus,
                             LocalDate startDate, LocalDate endDate, String email, String phone) {
    }

    private static final int MAX_MANAGER_DEPTH = 100;

    private final IdentityStore store;
    private final OrganizationDirectory org;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;

    public PersonService(IdentityStore store, OrganizationDirectory org, AccessGuard guard, AuditRecorder audit,
                         TransactionRunner tx, Clock clock) {
        this.store = store;
        this.org = org;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public PersonView create(CurrentActor actor, PersonData d) {
        return tx.inTransaction(() -> {
            ResourceScope scope = scopeOf(d.orgUnitId());
            guard.require(actor, Permissions.PERSON_WRITE, scope, false);
            Person p = toPerson(Ids.newId(clock), d, 0);
            validateReferences(p);
            store.insert(p);
            audit.record(actor, AuditEntry.success("person.created", "person", p.id(),
                    Map.of("orgUnitId", String.valueOf(p.orgUnitId()), "employeeId", String.valueOf(p.employeeId()))));
            return view(p);
        });
    }

    public PersonView update(CurrentActor actor, UUID id, PersonData d, long expectedVersion) {
        return tx.inTransaction(() -> {
            IdentityStore.Scoped<Person> current = store.findPerson(id).orElseThrow(() -> IamException.notFound("Person"));
            guard.require(actor, Permissions.PERSON_WRITE, ResourceScope.orgUnit(current.orgUnitPath()), true);
            boolean moved = !Objects.equals(current.value().orgUnitId(), d.orgUnitId());
            if (moved) {
                guard.require(actor, Permissions.PERSON_WRITE, scopeOf(d.orgUnitId()), false);
            }
            Person updated = toPerson(id, d, expectedVersion);
            validateReferences(updated);
            if (!store.update(updated, expectedVersion)) {
                throw IamException.concurrentModification("Person");
            }
            Map<String, String> details = new HashMap<>();
            if (moved) {
                details.put("fromOrgUnitId", String.valueOf(current.value().orgUnitId()));
                details.put("toOrgUnitId", String.valueOf(d.orgUnitId()));
            }
            audit.record(actor, AuditEntry.success(moved ? "person.moved" : "person.updated", "person", id, details));
            return view(toPerson(id, d, expectedVersion + 1));
        });
    }

    public PersonView get(CurrentActor actor, UUID id) {
        IdentityStore.Scoped<Person> p = tx.readOnly(() -> store.findPerson(id)).orElseThrow(() -> IamException.notFound("Person"));
        guard.require(actor, Permissions.PERSON_READ, scopeFromPath(p.orgUnitPath()), true);
        return view(p.value());
    }

    public PageResult<PersonView> list(CurrentActor actor, String search, PageRequest page) {
        var filter = guard.filter(actor, Permissions.PERSON_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.listPersons(filter, search, page), page.limit(), s -> s.value().id()))
                .map(s -> view(s.value()));
    }

    private void validateReferences(Person p) {
        if (p.orgUnitId() != null && org.orgUnitPath(p.orgUnitId()).isEmpty()) {
            throw IamException.validation("orgUnitId", "NOT_FOUND", "org unit does not exist");
        }
        if (p.positionId() != null && !org.positionExists(p.positionId())) {
            throw IamException.validation("positionId", "NOT_FOUND", "position does not exist");
        }
        if (p.locationId() != null && !org.locationExists(p.locationId())) {
            throw IamException.validation("locationId", "NOT_FOUND", "location does not exist");
        }
        if (p.employeeId() != null && store.employeeIdExists(p.employeeId(), p.id())) {
            throw IamException.alreadyExists("Employee id " + p.employeeId());
        }
        if (p.managerPersonId() != null) {
            if (store.findPerson(p.managerPersonId()).isEmpty()) {
                throw IamException.validation("managerPersonId", "NOT_FOUND", "manager does not exist");
            }
            Set<UUID> seen = new HashSet<>();
            UUID cursor = p.managerPersonId();
            for (int depth = 0; cursor != null && depth < MAX_MANAGER_DEPTH; depth++) {
                if (cursor.equals(p.id()) || !seen.add(cursor)) {
                    throw IamException.validation("managerPersonId", "CYCLE", "manager relationship would create a cycle");
                }
                cursor = store.managerOf(cursor).orElse(null);
            }
        }
    }

    private ResourceScope scopeOf(UUID orgUnitId) {
        if (orgUnitId == null) {
            return ResourceScope.PLATFORM;
        }
        return ResourceScope.orgUnit(org.orgUnitPath(orgUnitId)
                .orElseThrow(() -> IamException.validation("orgUnitId", "NOT_FOUND", "org unit does not exist")));
    }

    static ResourceScope scopeFromPath(String path) {
        return path == null ? ResourceScope.PLATFORM : ResourceScope.orgUnit(path);
    }

    private static Person toPerson(UUID id, PersonData d, long version) {
        return new Person(id, d.orgUnitId(), d.employeeId(), d.givenName(), d.familyName(), d.displayName(), d.positionId(),
                d.locationId(), d.managerPersonId(), d.employmentStatus() == null ? Person.EmploymentStatus.ACTIVE : d.employmentStatus(),
                d.startDate(), d.endDate(), d.email(), d.phone(), version);
    }

    static PersonView view(Person p) {
        return new PersonView(p.id(), p.orgUnitId(), p.employeeId(), p.givenName(), p.familyName(), p.displayName(), p.positionId(),
                p.locationId(), p.managerPersonId(), p.employmentStatus().name(), p.startDate(), p.endDate(), p.email(), p.phone(),
                p.version());
    }
}
