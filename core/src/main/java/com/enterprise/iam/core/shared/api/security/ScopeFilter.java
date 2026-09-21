package com.enterprise.iam.core.shared.api.security;

import java.util.List;
import java.util.Set;

/**
 * Visibility filter for list queries (spec §52): either global, or the union of the actor's scope grants for one
 * permission. Repositories translate it into SQL predicates; an empty non-global filter matches nothing.
 */
public record ScopeFilter(boolean global, List<Grant> grants) {

    public ScopeFilter {
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    public static final ScopeFilter NONE = new ScopeFilter(false, List.of());
    public static final ScopeFilter GLOBAL = new ScopeFilter(true, List.of());

    public boolean isEmpty() {
        return !global && grants.isEmpty();
    }

    /**
     * One scoped grant: every non-empty dimension must match (AND); values within a dimension are alternatives (OR).
     * {@code orgUnitPrefixes} match descendants (ORG_UNIT_TREE); {@code orgUnitPaths} match exactly (ORG_UNIT).
     */
    public record Grant(Set<String> orgUnitPaths, Set<String> orgUnitPrefixes, Set<String> environments,
                        Set<String> providerTypes, Set<String> providerInstanceIds, Set<String> targetIds) {
        public Grant {
            orgUnitPaths = Set.copyOf(orgUnitPaths);
            orgUnitPrefixes = Set.copyOf(orgUnitPrefixes);
            environments = Set.copyOf(environments);
            providerTypes = Set.copyOf(providerTypes);
            providerInstanceIds = Set.copyOf(providerInstanceIds);
            targetIds = Set.copyOf(targetIds);
        }

        public boolean constrainsOrg() {
            return !orgUnitPaths.isEmpty() || !orgUnitPrefixes.isEmpty();
        }

        /** In-memory evaluation, identical semantics to the SQL translation. */
        public boolean matches(ResourceScope r) {
            if (constrainsOrg()) {
                if (r.orgUnitPath() == null) {
                    return false;
                }
                boolean org = orgUnitPaths.contains(r.orgUnitPath())
                        || orgUnitPrefixes.stream().anyMatch(p -> r.orgUnitPath().startsWith(p));
                if (!org) {
                    return false;
                }
            }
            if (!environments.isEmpty() && (r.environment() == null || !environments.contains(r.environment()))) {
                return false;
            }
            if (!providerTypes.isEmpty() && (r.providerType() == null || !providerTypes.contains(r.providerType()))) {
                return false;
            }
            if (!providerInstanceIds.isEmpty() && (r.providerInstanceId() == null
                    || !providerInstanceIds.contains(r.providerInstanceId().toString()))) {
                return false;
            }
            return targetIds.isEmpty() || (r.targetId() != null && targetIds.contains(r.targetId().toString()));
        }
    }

    public boolean matches(ResourceScope resource) {
        return global || grants.stream().anyMatch(g -> g.matches(resource));
    }
}
