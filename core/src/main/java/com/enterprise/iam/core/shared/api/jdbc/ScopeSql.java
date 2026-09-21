package com.enterprise.iam.core.shared.api.jdbc;

import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@link ScopeFilter} into a SQL predicate with named parameters, using the same semantics as
 * {@link ScopeFilter.Grant#matches}: grants are OR-ed, dimensions inside a grant are AND-ed, values within a
 * dimension are OR-ed, and a NULL column never matches a constrained dimension (fail closed).
 * Column arguments are code constants, never user input.
 */
public final class ScopeSql {

    /** Column names of the scope dimensions for one table; null means the table has no such dimension. */
    public record Columns(String orgUnitPath, String environment, String providerType, String providerInstanceId, String targetId) {
    }

    private ScopeSql() {
    }

    /** @return predicate such as {@code (TRUE)}, {@code (FALSE)} or {@code ((a IN (...)) OR (...))} */
    public static String predicate(ScopeFilter filter, Columns c, Map<String, Object> params, String prefix) {
        if (filter.global()) {
            return "(TRUE)";
        }
        if (filter.isEmpty()) {
            return "(FALSE)";
        }
        List<String> ors = new ArrayList<>();
        int g = 0;
        for (ScopeFilter.Grant grant : filter.grants()) {
            List<String> ands = new ArrayList<>();
            String p = prefix + "g" + (g++) + "_";
            if (grant.constrainsOrg()) {
                if (c.orgUnitPath() == null) {
                    ands.add("FALSE");
                } else {
                    List<String> org = new ArrayList<>();
                    if (!grant.orgUnitPaths().isEmpty()) {
                        org.add(c.orgUnitPath() + " IN (:" + p + "orgExact)");
                        params.put(p + "orgExact", List.copyOf(grant.orgUnitPaths()));
                    }
                    int i = 0;
                    for (String prefixPath : grant.orgUnitPrefixes()) {
                        String name = p + "orgTree" + (i++);
                        org.add(c.orgUnitPath() + " LIKE :" + name);
                        params.put(name, escapeLike(prefixPath) + "%");
                    }
                    ands.add("(" + String.join(" OR ", org) + ")");
                }
            }
            addIn(ands, grant.environments(), c.environment(), p + "env", params);
            addIn(ands, grant.providerTypes(), c.providerType(), p + "ptype", params);
            addIn(ands, grant.providerInstanceIds(), c.providerInstanceId() == null ? null : c.providerInstanceId() + "::text", p + "pinst", params);
            addIn(ands, grant.targetIds(), c.targetId() == null ? null : c.targetId() + "::text", p + "target", params);
            ors.add(ands.isEmpty() ? "FALSE" : "(" + String.join(" AND ", ands) + ")");
        }
        return "(" + String.join(" OR ", ors) + ")";
    }

    private static void addIn(List<String> ands, Set<String> values, String column, String name, Map<String, Object> params) {
        if (values.isEmpty()) {
            return;
        }
        if (column == null) {
            ands.add("FALSE");
            return;
        }
        ands.add(column + " IN (:" + name + ")");
        params.put(name, List.copyOf(values));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
