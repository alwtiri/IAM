package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Account as returned by the API. Contains no credential material (credentials are only in Vault). */
public record AccountView(UUID id, UUID targetId, String targetName, UUID providerInstanceId, String providerType, String nativeId,
                          String name, String displayName, String type, boolean privileged, String privilegeReason,
                          UUID ownerIdentityId, UUID linkedIdentityId, String governanceState, String nativeStatus, String source,
                          Map<String, String> attributes, Instant lastSeenAt, Instant lastLoginAt, Instant passwordLastSetAt,
                          List<String> openFindings, long version) {
}
