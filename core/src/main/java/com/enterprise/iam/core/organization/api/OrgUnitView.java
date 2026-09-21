package com.enterprise.iam.core.organization.api;

import java.util.UUID;

public record OrgUnitView(UUID id, UUID parentId, String kind, String code, String name, String path, long version) {
}
