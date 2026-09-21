package com.enterprise.iam.provider.spi.model;

import com.enterprise.iam.provider.spi.CredentialHandle;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Desired properties of an account to create. Attributes are provider-specific and non-secret;
 * the initial credential, if any, is a handle.
 */
public record AccountSpec(
        String name,
        String displayName,
        Map<String, String> attributes,
        List<GroupRef> groups,
        CredentialHandle initialCredential) {

    public AccountSpec {
        Objects.requireNonNull(name, "name");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
