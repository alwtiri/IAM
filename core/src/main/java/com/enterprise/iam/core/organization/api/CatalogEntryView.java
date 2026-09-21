package com.enterprise.iam.core.organization.api;

import java.util.UUID;

/** Position or location. */
public record CatalogEntryView(UUID id, String code, String name, String detail) {
}
