package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for persons, identities, and platform users. */
public interface IdentityStore {

    /** A row with the org unit path used for scope decisions. */
    record Scoped<T>(T value, String orgUnitPath, String displayName, boolean platformUser) {
    }

    record PlatformUserRef(UUID identityId, String state) {
    }

    void insert(Person person);

    boolean update(Person person, long expectedVersion);

    Optional<Scoped<Person>> findPerson(UUID id);

    Optional<UUID> managerOf(UUID personId);

    boolean employeeIdExists(String employeeId, UUID exceptPersonId);

    List<Scoped<Person>> listPersons(ScopeFilter filter, String search, PageRequest page);

    void insert(Identity identity);

    boolean update(Identity identity, long expectedVersion);

    Optional<Scoped<Identity>> findIdentity(UUID id);

    boolean usernameExists(String username);

    Optional<UUID> identityIdByUsername(String username);

    List<Scoped<Identity>> listIdentities(ScopeFilter filter, UUID personId, String state, PageRequest page);

    List<Identity> findExpired(Instant now, int limit);

    Optional<IdentitySummary> summary(UUID identityId);

    Optional<PlatformUserRef> findBySubject(String subject);

    boolean subjectExists(String subject);

    boolean hasPlatformUser(UUID identityId);

    /** Keycloak subject linked to the identity, if any. */
    Optional<String> subjectOf(UUID identityId);

    void insertPlatformUser(UUID identityId, String subject, Instant now);

    void touchLogin(UUID identityId, Instant now);

    long platformUserCount();

    /** Inserts the one-time bootstrap marker; false if it already exists (ADR-0016). */
    boolean markBootstrapCompleted(Instant now, String subject);
}
