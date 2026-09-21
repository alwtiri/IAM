package com.enterprise.iam.provider.spi.model;

import java.util.List;
import java.util.Map;

/**
 * Desired target-side state computed by the Core (accounts that must exist with given status and memberships).
 * Objects not mentioned are out of scope for this call and must not be touched.
 */
public record DesiredState(Map<AccountRef, NativeAccountStatus> accounts, Map<GroupRef, List<AccountRef>> memberships) {
    public DesiredState {
        accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
        memberships = memberships == null ? Map.of() : Map.copyOf(memberships);
    }
}
