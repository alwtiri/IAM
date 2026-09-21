package com.enterprise.iam.provider.spi.model;

import java.util.List;
import java.util.Objects;

/** A group/role/entitlement container discovered on a target. */
public record DiscoveredGroup(GroupRef group, boolean privileged, List<AccountRef> members) {
    public DiscoveredGroup {
        Objects.requireNonNull(group, "group");
        members = members == null ? List.of() : List.copyOf(members);
    }
}
