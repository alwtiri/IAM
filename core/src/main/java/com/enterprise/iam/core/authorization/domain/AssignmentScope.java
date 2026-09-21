package com.enterprise.iam.core.authorization.domain;

import com.enterprise.iam.kernel.IamException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Non-empty set of scope elements; GLOBAL must stand alone (empty scope is never global — DOMAIN-MODEL §4). */
public record AssignmentScope(Set<ScopeElement> elements) {

    public AssignmentScope {
        if (elements == null || elements.isEmpty()) {
            throw IamException.validation("scope", "REQUIRED", "at least one scope element is required");
        }
        elements = Set.copyOf(new LinkedHashSet<>(elements));
        boolean global = elements.stream().anyMatch(e -> e.type() == ScopeElement.Type.GLOBAL);
        if (global && elements.size() > 1) {
            throw IamException.validation("scope", "INVALID", "GLOBAL scope cannot be combined with other elements");
        }
        if (elements.size() > 50) {
            throw IamException.validation("scope", "TOO_MANY", "max 50 scope elements");
        }
    }

    public static AssignmentScope global() {
        return new AssignmentScope(Set.of(ScopeElement.GLOBAL));
    }

    public boolean isGlobal() {
        return elements.contains(ScopeElement.GLOBAL);
    }

    public List<ScopeElement> orgElements() {
        return elements.stream().filter(ScopeElement::isOrg).toList();
    }

    public boolean hasOrg() {
        return elements.stream().anyMatch(ScopeElement::isOrg);
    }
}
