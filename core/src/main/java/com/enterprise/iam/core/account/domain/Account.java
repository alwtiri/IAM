package com.enterprise.iam.core.account.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An account on a target, reached through one provider instance (spec §12, G8).
 *
 * <p>Governance fields (type, owner, linked identity, governance state) are platform truth and only change through
 * governance actions. Native fields (status, privilege, last seen, last login) are what the target last reported and
 * are refreshed by discovery or by verified operations. Attributes hold non-secret metadata only.
 */
public record Account(UUID id, UUID targetId, UUID providerInstanceId, String nativeId, String name, String displayName,
                      Type type, boolean privileged, String privilegeReason, UUID ownerIdentityId, UUID linkedIdentityId,
                      GovernanceState governanceState, NativeStatus nativeStatus, Source source, Map<String, String> attributes,
                      Instant lastSeenAt, Instant lastLoginAt, Instant passwordLastSetAt, long version) {

    public enum Type { HUMAN, SERVICE, SYSTEM, SHARED, EMERGENCY, UNKNOWN }

    /** DISCOVERED: seen, not yet reviewed. GOVERNED: reviewed/owned. MANAGED: credential managed by the platform. */
    public enum GovernanceState { DISCOVERED, GOVERNED, MANAGED, EXCLUDED, REMOVED }

    public enum NativeStatus { ENABLED, DISABLED, LOCKED, EXPIRED, UNKNOWN, ABSENT }

    public enum Source { DISCOVERY, PLATFORM, IMPORT }

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        requireText(nativeId, "nativeId", 512);
        requireText(name, "name", 256);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(governanceState, "governanceState");
        Objects.requireNonNull(nativeStatus, "nativeStatus");
        Objects.requireNonNull(source, "source");
        attributes = attributes == null ? Map.of() : Map.copyOf(new TreeMap<>(attributes));
        for (String key : attributes.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("privatekey")) {
                throw new IllegalArgumentException("Secret-like account attribute '" + key + "' is not allowed");
            }
        }
        if (!privileged) {
            privilegeReason = null;
        }
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be 1.." + max + " characters");
        }
    }

    public boolean isEnabled() {
        return nativeStatus == NativeStatus.ENABLED;
    }

    /** Refreshes the native side from a discovery observation; governance fields stay untouched. */
    public Account observed(DiscoveredAccount d, Instant seenAt) {
        return new Account(id, targetId, providerInstanceId, nativeId, d.name(), d.displayName(), type, d.privileged(),
                d.privilegeReason(), ownerIdentityId, linkedIdentityId,
                governanceState == GovernanceState.REMOVED ? GovernanceState.DISCOVERED : governanceState,
                d.nativeStatus(), source, d.attributes(), seenAt, d.lastLoginAt(), d.passwordLastSetAt(), version);
    }

    /** The account was not reported by a complete discovery run. */
    public Account absent() {
        return new Account(id, targetId, providerInstanceId, nativeId, name, displayName, type, privileged, privilegeReason,
                ownerIdentityId, linkedIdentityId,
                governanceState == GovernanceState.DISCOVERED ? GovernanceState.REMOVED : governanceState,
                NativeStatus.ABSENT, source, attributes, lastSeenAt, lastLoginAt, passwordLastSetAt, version);
    }

    public Account governed(Type newType, UUID newOwner, UUID newLinkedIdentity, GovernanceState newState) {
        return new Account(id, targetId, providerInstanceId, nativeId, name, displayName, newType, privileged, privilegeReason,
                newOwner, newLinkedIdentity, newState, nativeStatus, source, attributes, lastSeenAt, lastLoginAt,
                passwordLastSetAt, version);
    }
}
