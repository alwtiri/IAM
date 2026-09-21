package com.enterprise.iam.core.organization.domain;

import com.enterprise.iam.kernel.IamException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Organizational unit (spec §5): business unit, department, or team in a tree. The materialized {@code path}
 * ({@code /<ancestor ids>/<own id>/}) drives ORG_UNIT_TREE scopes.
 */
public record OrgUnit(UUID id, UUID organizationId, UUID parentId, Kind kind, String code, String name, String path, long version) {

    public enum Kind {
        BUSINESS_UNIT(0), DEPARTMENT(1), TEAM(2);

        private final int rank;

        Kind(int rank) {
            this.rank = rank;
        }

        /** A child may not be of a broader kind than its parent (e.g. no business unit under a team). */
        public boolean mayContain(Kind child) {
            return child.rank >= rank;
        }
    }

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");

    public OrgUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        if (code == null || !CODE.matcher(code).matches()) {
            throw IamException.validation("code", "INVALID", "code must be 2-32 characters: A-Z, 0-9, _ or -");
        }
        name = requireName(name);
    }

    public static OrgUnit createRoot(UUID id, UUID organizationId, Kind kind, String code, String name) {
        return new OrgUnit(id, organizationId, null, kind, code, name, "/" + id + "/", 0);
    }

    public static OrgUnit createChild(UUID id, OrgUnit parent, Kind kind, String code, String name) {
        if (!parent.kind().mayContain(kind)) {
            throw IamException.validation("kind", "NOT_ALLOWED", "a " + parent.kind() + " cannot contain a " + kind);
        }
        return new OrgUnit(id, parent.organizationId(), parent.id(), kind, code, name, parent.path() + id + "/", 0);
    }

    public OrgUnit rename(String newName) {
        return new OrgUnit(id, organizationId, parentId, kind, code, newName, path, version);
    }

    static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw IamException.validation("name", "INVALID", "name is required (max 200 characters)");
        }
        return name.strip();
    }
}
