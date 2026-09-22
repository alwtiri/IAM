package com.enterprise.iam.core.identity;

import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.identity.application.IdentityStore;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link IdentityStore} with the same semantics as the JDBC adapter. */
final class MemoryIdentityStore implements IdentityStore {

    final Map<UUID, Person> persons = new LinkedHashMap<>();
    final Map<UUID, Identity> identities = new LinkedHashMap<>();
    final Map<String, UUID> subjects = new HashMap<>();
    final Map<UUID, String> orgPaths = new HashMap<>();
    boolean bootstrapMarked;

    private String path(Person p) {
        return p.orgUnitId() == null ? null : orgPaths.get(p.orgUnitId());
    }

    @Override
    public void insert(Person person) {
        persons.put(person.id(), person);
    }

    @Override
    public boolean update(Person person, long expectedVersion) {
        Person cur = persons.get(person.id());
        if (cur == null || cur.version() != expectedVersion) {
            return false;
        }
        persons.put(person.id(), new Person(person.id(), person.orgUnitId(), person.employeeId(), person.givenName(),
                person.familyName(), person.displayName(), person.positionId(), person.locationId(), person.managerPersonId(),
                person.employmentStatus(), person.startDate(), person.endDate(), person.email(), person.phone(), expectedVersion + 1));
        return true;
    }

    @Override
    public Optional<Scoped<Person>> findPerson(UUID id) {
        return Optional.ofNullable(persons.get(id)).map(p -> new Scoped<>(p, path(p), p.displayName(), false));
    }

    @Override
    public Optional<UUID> managerOf(UUID personId) {
        return Optional.ofNullable(persons.get(personId)).map(Person::managerPersonId);
    }

    @Override
    public boolean employeeIdExists(String employeeId, UUID exceptPersonId) {
        return persons.values().stream().anyMatch(p -> employeeId.equals(p.employeeId()) && !p.id().equals(exceptPersonId));
    }

    @Override
    public List<Scoped<Person>> listPersons(ScopeFilter filter, String search, PageRequest page) {
        return persons.values().stream().filter(p -> filter.matches(path(p) == null ? ResourceScope.PLATFORM : ResourceScope.orgUnit(path(p))))
                .map(p -> new Scoped<>(p, path(p), p.displayName(), false)).toList();
    }

    @Override
    public void insert(Identity identity) {
        identities.put(identity.id(), identity);
    }

    @Override
    public boolean update(Identity identity, long expectedVersion) {
        Identity cur = identities.get(identity.id());
        if (cur == null || cur.version() != expectedVersion) {
            return false;
        }
        identities.put(identity.id(), new Identity(identity.id(), identity.personId(), identity.type(), identity.username(), identity.state(),
                identity.stateReason(), identity.validFrom(), identity.validUntil(), expectedVersion + 1));
        return true;
    }

    @Override
    public Optional<Scoped<Identity>> findIdentity(UUID id) {
        return Optional.ofNullable(identities.get(id)).map(i -> {
            Person p = persons.get(i.personId());
            return new Scoped<>(i, path(p), p.displayName(), subjects.containsValue(i.id()));
        });
    }

    @Override
    public boolean usernameExists(String username) {
        return identities.values().stream().anyMatch(i -> i.username().equals(username));
    }

    @Override
    public Optional<UUID> identityIdByUsername(String username) {
        return identities.values().stream().filter(i -> i.username().equals(username)).map(Identity::id).findFirst();
    }

    @Override
    public List<Scoped<Identity>> listIdentities(ScopeFilter filter, UUID personId, String state, PageRequest page) {
        return identities.values().stream().map(i -> findIdentity(i.id()).orElseThrow())
                .filter(s -> filter.matches(s.orgUnitPath() == null ? ResourceScope.PLATFORM : ResourceScope.orgUnit(s.orgUnitPath())))
                .filter(s -> personId == null || s.value().personId().equals(personId))
                .filter(s -> state == null || s.value().state().name().equals(state)).toList();
    }

    @Override
    public List<Identity> findExpired(Instant now, int limit) {
        return identities.values().stream().filter(i -> i.isExpired(now)
                && (i.state() == IdentityState.ACTIVE || i.state() == IdentityState.SUSPENDED || i.state() == IdentityState.PENDING))
                .limit(limit).toList();
    }

    @Override
    public Optional<IdentitySummary> summary(UUID identityId) {
        return findIdentity(identityId).map(s -> {
            Person p = persons.get(s.value().personId());
            return new IdentitySummary(s.value().id(), p.id(), s.value().username(), p.displayName(), p.email(), s.value().type().name(),
                    s.value().state().name(), s.value().validUntil(), p.orgUnitId(), s.orgUnitPath());
        });
    }

    @Override
    public Optional<PlatformUserRef> findBySubject(String subject) {
        return Optional.ofNullable(subjects.get(subject)).map(id -> new PlatformUserRef(id, identities.get(id).state().name()));
    }

    @Override
    public boolean subjectExists(String subject) {
        return subjects.containsKey(subject);
    }

    @Override
    public boolean hasPlatformUser(UUID identityId) {
        return subjects.containsValue(identityId);
    }

    @Override
    public Optional<String> subjectOf(UUID identityId) {
        return subjects.entrySet().stream().filter(e -> e.getValue().equals(identityId)).map(Map.Entry::getKey).findFirst();
    }

    @Override
    public void insertPlatformUser(UUID identityId, String subject, Instant now) {
        Objects.requireNonNull(subject);
        subjects.put(subject, identityId);
    }

    @Override
    public void touchLogin(UUID identityId, Instant now) {
    }

    @Override
    public long platformUserCount() {
        return subjects.size();
    }

    @Override
    public boolean markBootstrapCompleted(Instant now, String subject) {
        if (bootstrapMarked) {
            return false;
        }
        bootstrapMarked = true;
        return true;
    }
}
