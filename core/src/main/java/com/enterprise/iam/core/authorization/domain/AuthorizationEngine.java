package com.enterprise.iam.core.authorization.domain;

import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure RBAC + scope evaluation (spec §19, PHASE-2-DESIGN §3.3). Input: the actor's effective assignments with their
 * role permissions; org-unit elements are resolved to current paths at evaluation time, so a moved org unit is
 * honoured immediately. Unresolvable org units contribute nothing (fail closed).
 */
public final class AuthorizationEngine {

    /** An effective assignment with its role's permissions. */
    public record EffectiveGrant(Set<String> permissions, AssignmentScope scope) {
        public EffectiveGrant {
            permissions = Set.copyOf(permissions);
        }
    }

    private AuthorizationEngine() {
    }

    public static ScopeFilter filter(List<EffectiveGrant> grants, String permission, Function<UUID, Optional<String>> orgPath) {
        List<ScopeFilter.Grant> out = new ArrayList<>();
        for (EffectiveGrant g : grants) {
            if (!g.permissions().contains(permission)) {
                continue;
            }
            if (g.scope().isGlobal()) {
                return ScopeFilter.GLOBAL;
            }
            Set<String> exact = new HashSet<>();
            Set<String> tree = new HashSet<>();
            Set<String> env = new HashSet<>();
            Set<String> ptype = new HashSet<>();
            Set<String> pinst = new HashSet<>();
            Set<String> target = new HashSet<>();
            for (ScopeElement e : g.scope().elements()) {
                switch (e.type()) {
                    case ORG_UNIT, ORG_UNIT_TREE -> {
                        Optional<String> path = orgPath.apply(UUID.fromString(e.value()));
                        if (path.isEmpty()) {
                            continue; // deleted/unknown org unit covers nothing
                        }
                        if (e.type() == ScopeElement.Type.ORG_UNIT) {
                            exact.add(path.get());
                        } else {
                            tree.add(path.get());
                        }
                    }
                    case ENVIRONMENT -> env.add(e.value());
                    case PROVIDER_TYPE -> ptype.add(e.value());
                    case PROVIDER_INSTANCE -> pinst.add(e.value());
                    case TARGET -> target.add(e.value());
                    case GLOBAL -> throw new IllegalStateException("handled above");
                    default -> throw new IllegalStateException();
                }
            }
            boolean orgConstrained = g.scope().hasOrg();
            if (orgConstrained && exact.isEmpty() && tree.isEmpty()) {
                continue; // every org element unresolved: grant covers nothing
            }
            out.add(new ScopeFilter.Grant(exact, tree, env, ptype, pinst, target));
        }
        return new ScopeFilter(false, out);
    }

    public static boolean isAllowed(List<EffectiveGrant> grants, String permission, ResourceScope resource,
                                    Function<UUID, Optional<String>> orgPath) {
        return filter(grants, permission, orgPath).matches(resource);
    }

    public static boolean holdsAnywhere(List<EffectiveGrant> grants, String permission) {
        return grants.stream().anyMatch(g -> g.permissions().contains(permission));
    }

    /** Convenience for tests and diagnostics. */
    public static Map<String, ScopeFilter> filters(List<EffectiveGrant> grants, Set<String> permissions,
                                                   Function<UUID, Optional<String>> orgPath) {
        Map<String, ScopeFilter> m = new java.util.TreeMap<>();
        permissions.forEach(p -> m.put(p, filter(grants, p, orgPath)));
        return m;
    }
}
