package com.enterprise.iam.core.authorization.domain;

import com.enterprise.iam.kernel.IamException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** One element of an assignment scope (PHASE-2-DESIGN §3.3). Values are validated per type. */
public record ScopeElement(Type type, String value) {

    public enum Type { GLOBAL, ORG_UNIT, ORG_UNIT_TREE, ENVIRONMENT, PROVIDER_TYPE, PROVIDER_INSTANCE, TARGET }

    public static final Set<String> ENVIRONMENTS = Set.of("PRODUCTION", "STAGING", "TEST", "DEVELOPMENT", "DR");
    private static final Pattern PROVIDER_TYPE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    public static final ScopeElement GLOBAL = new ScopeElement(Type.GLOBAL, "*");

    public ScopeElement {
        Objects.requireNonNull(type, "type");
        if (value == null) {
            throw IamException.validation("scope", "INVALID", "scope value is required");
        }
        switch (type) {
            case GLOBAL -> {
                if (!"*".equals(value)) {
                    throw IamException.validation("scope", "INVALID", "GLOBAL scope value must be '*'");
                }
            }
            case ORG_UNIT, ORG_UNIT_TREE, PROVIDER_INSTANCE, TARGET -> {
                try {
                    value = UUID.fromString(value).toString();
                } catch (IllegalArgumentException e) {
                    throw IamException.validation("scope", "INVALID", type + " scope value must be a UUID");
                }
            }
            case ENVIRONMENT -> {
                if (!ENVIRONMENTS.contains(value)) {
                    throw IamException.validation("scope", "INVALID", "unknown environment " + value);
                }
            }
            case PROVIDER_TYPE -> {
                if (!PROVIDER_TYPE.matcher(value).matches() || value.length() > 48) {
                    throw IamException.validation("scope", "INVALID", "invalid provider type " + value);
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    public boolean isOrg() {
        return type == Type.ORG_UNIT || type == Type.ORG_UNIT_TREE;
    }
}
