package com.enterprise.iam.core.policy.api;

import java.util.List;
import java.util.UUID;

public record PolicyView(UUID id, String code, String name, String description, boolean enabled, String effect, String requestType,
                         List<String> roleCodes, List<String> identityTypes, List<String> approvals, boolean requireJustification,
                         Integer maxDurationDays, long version) {
}
