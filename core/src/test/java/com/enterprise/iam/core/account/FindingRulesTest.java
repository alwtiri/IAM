package com.enterprise.iam.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.account.domain.FindingRules.Severity;
import com.enterprise.iam.core.account.domain.FindingRules.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §14 finding rules. */
class FindingRulesTest {

    static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    static final FindingRules.Context CTX = new FindingRules.Context(NOW, Duration.ofDays(90), false);

    static Account account(Account.Type type, boolean privileged, UUID owner, UUID linked, Account.GovernanceState gov,
                           Account.NativeStatus status, Instant lastLogin) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "n1", "alice", null, type, privileged,
                privileged ? "member of wheel" : null, owner, linked, gov, status, Account.Source.DISCOVERY, Map.of(), NOW, lastLogin,
                null, 0);
    }

    @Test
    void orphanIsEnabledHumanWithoutIdentity() {
        var f = FindingRules.evaluate(account(Account.Type.UNKNOWN, false, null, null, Account.GovernanceState.DISCOVERED,
                Account.NativeStatus.ENABLED, null), null, CTX);
        assertEquals(Map.of(Type.ORPHAN, Severity.MEDIUM), f);
    }

    @Test
    void disabledOrphanIsNotAFinding() {
        var f = FindingRules.evaluate(account(Account.Type.UNKNOWN, false, null, null, Account.GovernanceState.DISCOVERED,
                Account.NativeStatus.DISABLED, null), null, CTX);
        assertTrue(f.isEmpty());
    }

    @Test
    void privilegedWithoutOwnerIsUnmanagedOrphanAndOwnerless() {
        var f = FindingRules.evaluate(account(Account.Type.UNKNOWN, true, null, null, Account.GovernanceState.DISCOVERED,
                Account.NativeStatus.ENABLED, null), null, CTX);
        assertEquals(Severity.HIGH, f.get(Type.ORPHAN));
        assertEquals(Severity.HIGH, f.get(Type.UNMANAGED));
        assertEquals(Severity.HIGH, f.get(Type.PRIVILEGED_WITHOUT_OWNER));
    }

    @Test
    void managedPrivilegedServiceAccountWithOwnerIsClean() {
        var f = FindingRules.evaluate(account(Account.Type.SERVICE, true, UUID.randomUUID(), null, Account.GovernanceState.MANAGED,
                Account.NativeStatus.ENABLED, NOW.minusSeconds(3600)), null, CTX);
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void dormantAfterThreshold() {
        var f = FindingRules.evaluate(account(Account.Type.HUMAN, false, null, UUID.randomUUID(), Account.GovernanceState.GOVERNED,
                Account.NativeStatus.ENABLED, NOW.minus(Duration.ofDays(91))), new FindingRules.LinkedIdentity("ACTIVE"), CTX);
        assertEquals(Map.of(Type.DORMANT, Severity.LOW), f);
    }

    @Test
    void disabledIdentityWithEnabledAccountIsCritical() {
        var f = FindingRules.evaluate(account(Account.Type.HUMAN, false, null, UUID.randomUUID(), Account.GovernanceState.GOVERNED,
                Account.NativeStatus.ENABLED, null), new FindingRules.LinkedIdentity("DISABLED"), CTX);
        assertEquals(Map.of(Type.DISABLED_IDENTITY_ACTIVE_ACCOUNT, Severity.CRITICAL), f);
    }

    @Test
    void unexpectedOnlyOnBaselinedTarget() {
        Account a = account(Account.Type.SERVICE, false, UUID.randomUUID(), null, Account.GovernanceState.DISCOVERED,
                Account.NativeStatus.ENABLED, null);
        assertTrue(FindingRules.evaluate(a, null, CTX).isEmpty());
        assertEquals(Map.of(Type.UNEXPECTED, Severity.MEDIUM),
                FindingRules.evaluate(a, null, new FindingRules.Context(NOW, Duration.ofDays(90), true)));
    }

    @Test
    void excludedAndAbsentAccountsHaveNoFindings() {
        assertTrue(FindingRules.evaluate(account(Account.Type.UNKNOWN, true, null, null, Account.GovernanceState.EXCLUDED,
                Account.NativeStatus.ENABLED, null), null, CTX).isEmpty());
        assertTrue(FindingRules.evaluate(account(Account.Type.UNKNOWN, true, null, null, Account.GovernanceState.DISCOVERED,
                Account.NativeStatus.ABSENT, null), null, CTX).isEmpty());
    }
}
