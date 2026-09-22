package com.enterprise.iam.core.account.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Detective account findings (spec §14). Pure function of the account, its linked identity, and context, so the same
 * rules run after discovery, after governance changes, and in tests.
 *
 * <p>Rules:
 * <ul>
 *   <li>ORPHAN — enabled human/unknown account without a linked identity.</li>
 *   <li>UNMANAGED — privileged account whose credential is not managed by the platform.</li>
 *   <li>DORMANT — enabled account whose last login is older than the dormancy threshold.</li>
 *   <li>UNEXPECTED — account that appeared on an already baselined target outside the platform.</li>
 *   <li>PRIVILEGED_WITHOUT_OWNER — privileged account with neither owner nor linked identity.</li>
 *   <li>DISABLED_IDENTITY_ACTIVE_ACCOUNT — linked identity is suspended/disabled/archived but the account is enabled.</li>
 * </ul>
 * EXCLUDED and REMOVED accounts produce no findings.
 */
public final class FindingRules {

    public enum Type { ORPHAN, UNMANAGED, DORMANT, UNEXPECTED, PRIVILEGED_WITHOUT_OWNER, DISABLED_IDENTITY_ACTIVE_ACCOUNT }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    /** Linked identity state as seen by the identity module (PENDING, ACTIVE, SUSPENDED, DISABLED, ARCHIVED). */
    public record LinkedIdentity(String state) {
    }

    /**
     * @param dormantAfter       last-login age after which an enabled account is dormant
     * @param newOnBaselinedTarget true when the account was first seen after the target already had a completed discovery
     */
    public record Context(Instant now, Duration dormantAfter, boolean newOnBaselinedTarget) {
        public Context {
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(dormantAfter, "dormantAfter");
        }
    }

    private FindingRules() {
    }

    public static Map<Type, Severity> evaluate(Account a, LinkedIdentity linked, Context ctx) {
        Map<Type, Severity> out = new EnumMap<>(Type.class);
        if (a.governanceState() == Account.GovernanceState.EXCLUDED || a.governanceState() == Account.GovernanceState.REMOVED
                || a.nativeStatus() == Account.NativeStatus.ABSENT) {
            return out;
        }
        boolean enabled = a.isEnabled();
        boolean humanLike = a.type() == Account.Type.HUMAN || a.type() == Account.Type.UNKNOWN;

        if (enabled && humanLike && a.linkedIdentityId() == null) {
            out.put(Type.ORPHAN, a.privileged() ? Severity.HIGH : Severity.MEDIUM);
        }
        if (a.privileged() && a.governanceState() != Account.GovernanceState.MANAGED) {
            out.put(Type.UNMANAGED, Severity.HIGH);
        }
        if (enabled && a.lastLoginAt() != null && a.lastLoginAt().isBefore(ctx.now().minus(ctx.dormantAfter()))) {
            out.put(Type.DORMANT, a.privileged() ? Severity.MEDIUM : Severity.LOW);
        }
        if (ctx.newOnBaselinedTarget() && a.source() == Account.Source.DISCOVERY
                && a.governanceState() == Account.GovernanceState.DISCOVERED) {
            out.put(Type.UNEXPECTED, a.privileged() ? Severity.HIGH : Severity.MEDIUM);
        }
        if (a.privileged() && a.ownerIdentityId() == null && a.linkedIdentityId() == null) {
            out.put(Type.PRIVILEGED_WITHOUT_OWNER, Severity.HIGH);
        }
        if (enabled && linked != null && linked.state() != null
                && (linked.state().equals("SUSPENDED") || linked.state().equals("DISABLED") || linked.state().equals("ARCHIVED"))) {
            out.put(Type.DISABLED_IDENTITY_ACTIVE_ACCOUNT, Severity.CRITICAL);
        }
        return out;
    }
}
