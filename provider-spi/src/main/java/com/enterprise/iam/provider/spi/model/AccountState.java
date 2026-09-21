package com.enterprise.iam.provider.spi.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observed state of an account on a target (discovery, state read, verification). No secret values. */
public record AccountState(
        AccountRef account,
        NativeAccountStatus status,
        boolean privileged,
        List<GroupRef> groups,
        Instant lastLogin,
        Instant passwordLastSet,
        Instant passwordExpires,
        Map<String, String> attributes) {

    public AccountState {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(status, "status");
        groups = groups == null ? List.of() : List.copyOf(groups);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
