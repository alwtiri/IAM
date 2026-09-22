package com.enterprise.iam.core.account.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One account as reported by a provider's {@code DISCOVER_ACCOUNTS} page. Read-only observation: importing it never
 * changes the target.
 *
 * @param entitlementNativeIds native ids of the groups/roles the account belongs to (may be empty if unknown)
 */
public record DiscoveredAccount(String nativeId, String name, String displayName, Account.NativeStatus nativeStatus,
                                boolean privileged, String privilegeReason, Instant lastLoginAt, Instant passwordLastSetAt,
                                Map<String, String> attributes, List<String> entitlementNativeIds) {

    public DiscoveredAccount {
        Objects.requireNonNull(nativeId, "nativeId");
        Objects.requireNonNull(name, "name");
        nativeStatus = nativeStatus == null ? Account.NativeStatus.UNKNOWN : nativeStatus;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        entitlementNativeIds = entitlementNativeIds == null ? List.of() : List.copyOf(entitlementNativeIds);
    }
}
