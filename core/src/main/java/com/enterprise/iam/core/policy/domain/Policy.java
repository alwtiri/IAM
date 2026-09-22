package com.enterprise.iam.core.policy.domain;

import java.util.List;
import java.util.UUID;

/** One access policy. {@code roleCodes}/{@code identityTypes} null means "any". */
public record Policy(UUID id, String code, String name, String description, boolean enabled, String effect, String requestType,
                     List<String> roleCodes, List<String> identityTypes, List<String> approvals, boolean requireJustification,
                     Integer maxDurationDays, long version) {

    public Policy {
        roleCodes = roleCodes == null ? null : List.copyOf(roleCodes);
        identityTypes = identityTypes == null ? null : List.copyOf(identityTypes);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
    }

    public boolean matches(String type, String roleCode, String identityType) {
        return enabled && requestType.equals(type)
                && (roleCodes == null || roleCodes.contains(roleCode))
                && (identityTypes == null || identityTypes.contains(identityType));
    }
}
