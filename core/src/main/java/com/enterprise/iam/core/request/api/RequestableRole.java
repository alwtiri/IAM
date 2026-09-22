package com.enterprise.iam.core.request.api;

import java.util.List;
import java.util.UUID;

/** A role as offered in the request form, with what the policy will require for the current user. */
public record RequestableRole(UUID id, String code, String name, String description, boolean held, boolean allowed,
                              List<String> approvals, boolean requireJustification, Integer maxDurationDays, String explanation) {
}
