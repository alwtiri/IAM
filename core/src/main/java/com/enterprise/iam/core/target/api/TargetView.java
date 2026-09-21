package com.enterprise.iam.core.target.api;

import java.util.List;
import java.util.UUID;

public record TargetView(UUID id, String name, String hostname, String ipAddress, String dnsName, String type, String platform,
                         String operatingSystem, String environment, String criticality, String classification, UUID ownerOrgUnitId,
                         UUID ownerIdentityId, UUID technicalOwnerIdentityId, UUID businessOwnerIdentityId, UUID locationId,
                         List<String> tags, String status, String discoveryState, String reconciliationState, String health, long version) {
}
