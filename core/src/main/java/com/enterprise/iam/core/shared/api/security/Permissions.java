package com.enterprise.iam.core.shared.api.security;

import java.util.List;

/**
 * Permission catalog (DOMAIN-MODEL §4). Codes are {@code resource:action}. The catalog is seeded into
 * {@code authorization.permission} by migrations (V6, V11, …); {@code PermissionCatalogTest} keeps code and seeds in sync.
 */
public final class Permissions {

    public static final String SYSTEM_READ = "system:read";
    public static final String SYSTEM_HEALTH_READ = "system:health:read";
    public static final String ORG_READ = "org:read";
    public static final String ORG_WRITE = "org:write";
    public static final String PERSON_READ = "person:read";
    public static final String PERSON_WRITE = "person:write";
    public static final String IDENTITY_READ = "identity:read";
    public static final String IDENTITY_WRITE = "identity:write";
    public static final String IDENTITY_LIFECYCLE = "identity:lifecycle";
    public static final String IDENTITY_PLATFORM_USER = "identity:platform-user";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_ASSIGNMENT_READ = "role-assignment:read";
    public static final String ROLE_ASSIGNMENT_WRITE = "role-assignment:write";
    public static final String AUDIT_READ = "audit:read";
    public static final String AUDIT_VERIFY = "audit:verify";
    public static final String OPERATION_READ = "operation:read";
    public static final String TARGET_READ = "target:read";
    public static final String TARGET_WRITE = "target:write";
    public static final String PROVIDER_READ = "provider:read";
    public static final String PROVIDER_WRITE = "provider:write";
    public static final String ACCOUNT_READ = "account:read";
    public static final String ACCOUNT_WRITE = "account:write";
    public static final String ACCOUNT_DISCOVER = "account:discover";
    public static final String ACCOUNT_FINDING_RESOLVE = "account:finding:resolve";
    public static final String OPERATION_EXECUTE = "operation:execute";
    public static final String POLICY_READ = "policy:read";
    public static final String POLICY_WRITE = "policy:write";
    public static final String SOD_READ = "sod:read";
    public static final String REQUEST_READ = "request:read";

    /** All permission codes, in catalog order. */
    public static final List<String> ALL = List.of(
            SYSTEM_READ, SYSTEM_HEALTH_READ, ORG_READ, ORG_WRITE, PERSON_READ, PERSON_WRITE,
            IDENTITY_READ, IDENTITY_WRITE, IDENTITY_LIFECYCLE, IDENTITY_PLATFORM_USER,
            ROLE_READ, ROLE_ASSIGNMENT_READ, ROLE_ASSIGNMENT_WRITE, AUDIT_READ, AUDIT_VERIFY,
            OPERATION_READ, TARGET_READ, TARGET_WRITE, PROVIDER_READ, PROVIDER_WRITE,
            ACCOUNT_READ, ACCOUNT_WRITE, ACCOUNT_DISCOVER, ACCOUNT_FINDING_RESOLVE, OPERATION_EXECUTE,
            POLICY_READ, POLICY_WRITE, SOD_READ, REQUEST_READ);

    private Permissions() {
    }
}
