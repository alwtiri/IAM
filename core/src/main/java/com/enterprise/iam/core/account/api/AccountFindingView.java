package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AccountFindingView(UUID id, UUID accountId, String accountName, UUID targetId, String targetName, String type,
                                 String severity, Instant detectedAt, Instant resolvedAt, String resolution, Map<String, String> details) {
}
