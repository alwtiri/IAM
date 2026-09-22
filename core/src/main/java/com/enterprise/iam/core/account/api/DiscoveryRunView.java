package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

public record DiscoveryRunView(UUID id, UUID providerInstanceId, UUID targetId, UUID operationId, String status, Instant startedAt,
                               Instant finishedAt, int accountsSeen, int accountsNew, int accountsRemoved, int groupsSeen,
                               String errorMessage) {
}
