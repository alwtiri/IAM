package com.enterprise.iam.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.identity.api.IdentityView;
import com.enterprise.iam.core.identity.api.PersonView;
import com.enterprise.iam.core.identity.application.IdentityService;
import com.enterprise.iam.core.identity.application.LoginAccountProvisioner;
import com.enterprise.iam.core.identity.application.PersonService;
import com.enterprise.iam.core.identity.application.PlatformLoginService;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformLoginServiceTest {

    static final class FakeKeycloak implements LoginAccountProvisioner {
        boolean enabled = true;
        boolean mailFails;
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String createOrFind(NewLogin login) {
            calls.add("create " + login.username() + " " + login.email());
            return "kc-" + login.username();
        }

        @Override
        public void sendInvitation(String subject) {
            calls.add("invite " + subject);
            if (mailFails) {
                throw new IllegalStateException("smtp down");
            }
        }

        @Override
        public void setEnabled(String subject, boolean enabled) {
            calls.add((enabled ? "enable " : "disable ") + subject);
        }
    }

    private final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
    private final MemoryIdentityStore store = new MemoryIdentityStore();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final OrganizationDirectory org = new OrganizationDirectory() {
        @Override
        public Optional<String> orgUnitPath(UUID id) {
            return Optional.empty();
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
    private final IdentityService identities = new IdentityService(store, TestSupport.guard(true), (a, e) -> audit.add(e), e -> { },
            TestSupport.DIRECT_TX, clock);
    private final FakeKeycloak keycloak = new FakeKeycloak();
    private final PlatformLoginService logins = new PlatformLoginService(identities, store, keycloak, TestSupport.guard(true),
            (a, e) -> audit.add(e), TestSupport.DIRECT_TX);
    private final CurrentActor admin = TestSupport.actor(UUID.randomUUID());

    private IdentityView identity(String email, boolean activate) {
        PersonView p = persons.create(admin, new PersonService.PersonData(null, null, "Sara", "Ali", null, null, null, null,
                Person.EmploymentStatus.ACTIVE, null, null, email, null));
        IdentityView i = identities.create(admin, p.id(), IdentityType.EMPLOYEE, "sara.ali", null);
        return activate ? identities.transition(admin, i.id(), IdentityState.ACTIVE, "new") : i;
    }

    @Test
    void createsKeycloakLoginLinksItAndSendsTheInvitation() {
        IdentityView i = identity("sara@example.org", true);
        PlatformLoginService.Provisioned r = logins.provision(admin, i.id());
        assertEquals("kc-sara.ali", r.subject());
        assertTrue(r.invitationSent());
        assertEquals(List.of("create sara.ali sara@example.org", "invite kc-sara.ali"), keycloak.calls);
        assertTrue(identities.get(admin, i.id()).platformUser());
        assertTrue(audit.stream().anyMatch(a -> a.action().equals("identity.login-provisioned")));
        assertThrows(IamException.class, () -> logins.provision(admin, i.id()), "only one login per identity");
    }

    @Test
    void preconditionsAreEnforcedBeforeAnythingIsCreatedInKeycloak() {
        IdentityView pending = identity("sara@example.org", false);
        assertEquals("NOT_ACTIVE", assertThrows(IamException.class, () -> logins.provision(admin, pending.id())).details().get(0).code());
        assertTrue(keycloak.calls.isEmpty());
    }

    @Test
    void missingEmailOrDisabledAdminApiIsReported() {
        IdentityView noMail = identity(null, true);
        assertEquals("REQUIRED", assertThrows(IamException.class, () -> logins.provision(admin, noMail.id())).details().get(0).code());
        keycloak.enabled = false;
        assertFalse(logins.available());
        assertTrue(keycloak.calls.isEmpty());
    }

    @Test
    void failedInvitationKeepsTheLinkAndReportsIt() {
        keycloak.mailFails = true;
        IdentityView i = identity("sara@example.org", true);
        assertFalse(logins.provision(admin, i.id()).invitationSent());
        assertTrue(identities.get(admin, i.id()).platformUser());
    }

    @Test
    void lifecycleChangesBlockAndUnblockTheLogin() {
        IdentityView i = identity("sara@example.org", true);
        logins.provision(admin, i.id());
        logins.onLifecycleChanged(i.id(), "SUSPENDED");
        logins.onLifecycleChanged(i.id(), "ACTIVE");
        assertTrue(keycloak.calls.containsAll(List.of("disable kc-sara.ali", "enable kc-sara.ali")));
    }
}
