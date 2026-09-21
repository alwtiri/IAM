package com.enterprise.iam.core.organization.domain;

import com.enterprise.iam.kernel.IamException;
import java.util.Objects;
import java.util.UUID;

/** Position or location catalog entry. {@code detail} holds the location's country/time zone or the position title. */
public record CatalogEntry(UUID id, String code, String name, String detail) {

    public CatalogEntry {
        Objects.requireNonNull(id, "id");
        if (code == null || !OrgUnit.CODE.matcher(code).matches()) {
            throw IamException.validation("code", "INVALID", "code must be 2-32 characters: A-Z, 0-9, _ or -");
        }
        name = OrgUnit.requireName(name);
        if (detail != null && detail.length() > 200) {
            throw IamException.validation("detail", "TOO_LONG", "max 200 characters");
        }
    }
}
