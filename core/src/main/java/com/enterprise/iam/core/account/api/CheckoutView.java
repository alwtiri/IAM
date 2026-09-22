package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

public record CheckoutView(UUID id, UUID accountId, String accountName, UUID targetId, String targetName, UUID identityId, String identityName,
                           UUID requestId, String reason, Instant startedAt, Instant notAfter, String status, Instant endedAt,
                           String endedByName, int revealCount, Instant lastRevealedAt) {
}
