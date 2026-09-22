package com.enterprise.iam.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.identity.api.IdentityView;
import com.enterprise.iam.core.identity.api.PersonView;
import com.enterprise.iam.core.identity.application.ActorResolver;
import com.enterprise.iam.core.identity.application.IdentityService;
import com.enterprise.iam.core.identity.application.IdentityStore;
import com.enterprise.iam.core.identity.application.PersonService;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityServicesTest {

    private final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-21T10:00:00Z"));
    private final MemoryIdentityStore store = new MemoryIdentityStore();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final List<UUID> bootstrapGrants = new ArrayList<>();
    private final OrganizationDirectory org = new OrganizationDirectory() {
        @Override
        public Optional<String> orgUnitPath(UUID id) {
            return Optional.ofNullable(store.orgPaths.get(id));
        }

        @Override
        public boolean positionExists(UUID id) {
            return false;
        }

        @Override
        public boolean locationExists(UUID id) {
            return false;
        }
    };
    private final PersonService persons = new PersonService(store, org, TestSupport.guard(true), (a, e) -> audit.add(e), TestSupport.DIRECT_TX, clock);
    private final IdentityService identities = new IdentityService(store, TestSupport.guard(true), (a, e) -> audit.add(e), events::add,
            TestSupport.DIRECT_TX, clock);
    private final CurrentActor admin = TestSupport.actor(UUID.randomUUID());

    private PersonView person(String given, UUID manager) {
        return persons.create(admin, new PersonService.PersonData(null, null, given, "Test", null, null, null, manager,
                Person.EmploymentStatus.ACTIVE, null, null, given.toLowerCase() + "@example.org", null));
    }

    @Test
    void managerCyclesAreRejected() {
        PersonView a = person("Alice", null);
        PersonView b = person("Bob", a.id());
        IamException e = assertThrows(IamException.class, () -> persons.update(admin, a.id(), new PersonService.PersonData(null, null,
                "Alice", "Test", null, null, null, b.id(), Person.EmploymentStatus.ACTIVE, null, null, null, null), a.version()));
        assertEquals("CYCLE", e.details().get(0).code());
    }

    @Test
    void optimisticLockingDetectsStaleUpdates() {
        PersonView a = person("Alice", null);
        PersonService.PersonData d = new PersonService.PersonData(null, null, "Alicia", "Test", null, null, null, null,
                Person.EmploymentStatus.ACTIVE, null, null, null, null);
        persons.update(admin, a.id(), d, 0);
        IamException e = assertThrows(IamException.class, () -> persons.update(admin, a.id(), d, 0));
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, e.code());
    }

    @Test
    void lifecycleTransitionsAreValidatedAuditedAndPublished() {
        PersonView p = person("Carol", null);
        IdentityView i = identities.create(admin, p.id(), IdentityType.EMPLOYEE, "carol", null);
        assertEquals("PENDING", i.state());
        identities.transition(admin, i.id(), IdentityState.ACTIVE, null);
        assertThrows(IamException.class, () -> identities.transition(admin, i.id(), IdentityState.SUSPENDED, " "));
        IdentityView s = identities.transition(admin, i.id(), IdentityState.SUSPENDED, "investigation");
        assertEquals("SUSPENDED", s.state());
        IamException bad = assertThrows(IamException.class, () -> identities.transition(admin, i.id(), IdentityState.ARCHIVED, "x"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, bad.code());
        assertTrue(events.stream().allMatch(e -> e instanceof IdentityLifecycleChanged));
        assertEquals(2, events.size());
        assertTrue(audit.stream().anyMatch(e -> e.action().equals("identity.suspended")));
    }

    @Test
    void createUserIsOneStepAndRejectsDuplicateUsernamesWithoutOrphans() {
        var users = new com.enterprise.iam.core.identity.application.UserAdministrationService(persons, identities, store, TestSupport.DIRECT_TX);
        PersonService.PersonData data = new PersonService.PersonData(null, null, "Heidi", "Klum", null, null, null, null,
                Person.EmploymentStatus.ACTIVE, null, null, "heidi@example.org", null);
        IdentityView u = users.create(admin, new com.enterprise.iam.core.identity.application.UserAdministrationService.CreateUser(
                data, IdentityType.EMPLOYEE, "Heidi.Klum", true));
        assertEquals("heidi.klum", u.username());
        assertEquals("ACTIVE", u.state());
        int before = store.persons.size();
        assertThrows(IamException.class, () -> users.create(admin, new com.enterprise.iam.core.identity.application.UserAdministrationService.CreateUser(
                data, IdentityType.EMPLOYEE, "heidi.klum", true)), "duplicate username");
        assertThrows(IamException.class, () -> users.create(admin, new com.enterprise.iam.core.identity.application.UserAdministrationService.CreateUser(
                data, IdentityType.EMPLOYEE, "-bad", true)), "invalid username");
        assertEquals(before, store.persons.size(), "no orphan person");
    }

    @Test
    void userListFiltersBySearchStateAndType() {
        identities.create(admin, person("Frank", null).id(), IdentityType.EMPLOYEE, "frank.miller", null);
        IdentityView g = identities.create(admin, person("Grace", null).id(), IdentityType.EMPLOYEE, "grace.hopper", null);
        identities.transition(admin, g.id(), IdentityState.ACTIVE, null);
        var page = com.enterprise.iam.core.shared.api.paging.PageRequest.of(100, null);
        assertEquals(List.of("grace.hopper"), identities.list(admin, new IdentityStore.IdentityQuery(null, null, null, null, "HOPP"), page)
                .items().stream().map(IdentityView::username).toList());
        assertEquals(List.of("grace.hopper"), identities.list(admin, new IdentityStore.IdentityQuery(null, "ACTIVE", "EMPLOYEE", null, null), page)
                .items().stream().map(IdentityView::username).filter(u -> u.equals("grace.hopper") || u.equals("frank.miller")).toList());
        assertTrue(identities.list(admin, new IdentityStore.IdentityQuery(null, null, "CONTRACTOR", null, null), page).items().isEmpty());
        assertThrows(IamException.class, () -> identities.list(admin, new IdentityStore.IdentityQuery(null, null, null, null, "x".repeat(101)), page));
    }

    @Test
    void cannotChangeOwnIdentityState() {
        PersonView p = person("Dan", null);
        IdentityView i = identities.create(admin, p.id(), IdentityType.EMPLOYEE, "dan", null);
        IamException e = assertThrows(IamException.class,
                () -> identities.transition(TestSupport.actor(i.id()), i.id(), IdentityState.ACTIVE, null));
        assertEquals(ErrorCode.ACCESS_DENIED, e.code());
    }

    @Test
    void typeRulesAndExpiry() {
        PersonView p = person("Erin", null);
        assertThrows(IamException.class, () -> identities.create(admin, p.id(), IdentityType.CONTRACTOR, "erin-c", null));
        assertThrows(IamException.class, () -> identities.create(admin, p.id(), IdentityType.SYSTEM, "erin-s", null));
        IdentityView t = identities.create(admin, p.id(), IdentityType.TEMPORARY, "erin-t", clock.instant().plusSeconds(60));
        identities.transition(admin, t.id(), IdentityState.ACTIVE, null);
        clock.advanceSeconds(61);
        assertEquals(1, identities.expireDue(100));
        assertEquals("DISABLED", identities.get(admin, t.id()).state());
        assertThrows(IamException.class, () -> identities.create(admin, p.id(), IdentityType.EMPLOYEE, "erin-t", null)); // duplicate username
    }

    @Test
    void unknownSubjectIsDeniedAndBootstrapRunsExactlyOnce() {
        ActorResolver resolver = new ActorResolver(store, bootstrapGrants::add, (a, e) -> audit.add(e), TestSupport.DIRECT_TX, clock, "kc-admin-sub");
        IamException denied = assertThrows(IamException.class, () -> resolver.resolve(principal("stranger")));
        assertEquals(ErrorCode.ACCESS_DENIED, denied.code());
        assertEquals("auth.access.denied", audit.get(audit.size() - 1).action());

        CurrentActor first = resolver.resolve(principal("kc-admin-sub"));
        assertEquals(1, bootstrapGrants.size());
        assertEquals(first.identityId(), bootstrapGrants.get(0));
        assertEquals("platform.bootstrap", audit.get(audit.size() - 1).action());
        assertEquals(first.identityId(), resolver.resolve(principal("kc-admin-sub")).identityId(), "second login resolves normally");

        store.subjects.clear(); // even if platform users disappeared, the marker prevents a second bootstrap
        assertThrows(IamException.class, () -> resolver.resolve(principal("kc-admin-sub")));
        assertEquals(1, bootstrapGrants.size());
    }

    @Test
    void bearerTokensNeverBootstrapAndInactiveIdentitiesAreDenied() {
        ActorResolver resolver = new ActorResolver(store, bootstrapGrants::add, (a, e) -> audit.add(e), TestSupport.DIRECT_TX, clock, "kc-admin-sub");
        ActorResolver.AuthenticatedPrincipal bearer = new ActorResolver.AuthenticatedPrincipal("kc-admin-sub", null, List.of(), null,
                CurrentActor.Channel.BEARER_TOKEN, "10.0.0.1", "admin", null, null, null);
        assertThrows(IamException.class, () -> resolver.resolve(bearer));
        assertTrue(bootstrapGrants.isEmpty());

        PersonView p = person("Frank", null);
        IdentityView i = identities.create(admin, p.id(), IdentityType.EMPLOYEE, "frank", null);
        identities.linkPlatformUser(admin, i.id(), "sub-frank");
        assertThrows(IamException.class, () -> resolver.resolve(principal("sub-frank")), "PENDING identity cannot log in");
        identities.transition(admin, i.id(), IdentityState.ACTIVE, null);
        assertEquals(i.id(), resolver.resolve(principal("sub-frank")).identityId());
        assertThrows(IamException.class, () -> identities.linkPlatformUser(admin, i.id(), "sub-other"));
    }

    private static ActorResolver.AuthenticatedPrincipal principal(String sub) {
        return new ActorResolver.AuthenticatedPrincipal(sub, "mfa", List.of("otp"), Instant.parse("2026-09-21T09:59:00Z"),
                CurrentActor.Channel.BROWSER_SESSION, "10.0.0.1", "Admin.User", "Ada", "Admin", "ada@example.org");
    }
}
