package com.enterprise.iam.provider.spi;

/**
 * Operations of the provider contract (spec §11), each mapped to the capability it requires and
 * whether it changes target state. Mutating operations can only succeed with a verification record.
 */
public enum ProviderOperation {
    VALIDATE_CONNECTION(Capability.CONNECTION_VALIDATION, false),
    DISCOVER(Capability.ACCOUNT_DISCOVERY, false),
    DISCOVER_ACCOUNTS(Capability.ACCOUNT_DISCOVERY, false),
    DISCOVER_GROUPS(Capability.GROUP_DISCOVERY, false),
    DISCOVER_TARGETS(Capability.TARGET_DISCOVERY, false),
    GET_ACCOUNT_STATE(Capability.ACCOUNT_STATE_READ, false),
    CREATE_ACCOUNT(Capability.ACCOUNT_CREATE, true),
    ENABLE_ACCOUNT(Capability.ACCOUNT_ENABLE, true),
    DISABLE_ACCOUNT(Capability.ACCOUNT_DISABLE, true),
    UNLOCK_ACCOUNT(Capability.ACCOUNT_UNLOCK, true),
    DELETE_ACCOUNT(Capability.ACCOUNT_DELETE, true),
    CHANGE_PASSWORD(Capability.PASSWORD_CHANGE, true),
    RESET_PASSWORD(Capability.PASSWORD_RESET, true),
    ROTATE_PASSWORD(Capability.PASSWORD_ROTATION, true),
    CREATE_GROUP(Capability.GROUP_MANAGEMENT, true),
    MODIFY_GROUP(Capability.GROUP_MANAGEMENT, true),
    ADD_GROUP_MEMBER(Capability.GROUP_MANAGEMENT, true),
    REMOVE_GROUP_MEMBER(Capability.GROUP_MANAGEMENT, true),
    MOVE_OBJECT(Capability.OBJECT_MOVE, true),
    APPLY_DESIRED_STATE(Capability.DESIRED_STATE, true),
    VERIFY_OPERATION(Capability.ACCOUNT_STATE_READ, false),
    RECONCILE(Capability.RECONCILIATION, false);

    private final Capability requiredCapability;
    private final boolean mutating;

    ProviderOperation(Capability requiredCapability, boolean mutating) {
        this.requiredCapability = requiredCapability;
        this.mutating = mutating;
    }

    public Capability requiredCapability() {
        return requiredCapability;
    }

    public boolean mutating() {
        return mutating;
    }
}
