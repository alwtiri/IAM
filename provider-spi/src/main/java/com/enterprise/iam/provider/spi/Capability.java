package com.enterprise.iam.provider.spi;

/**
 * Capabilities a provider may expose for a target (spec §11, §18).
 * New constants may be added in minor SPI versions; consumers must tolerate unknown values.
 */
public enum Capability {
    CONNECTION_VALIDATION,
    ACCOUNT_DISCOVERY,
    GROUP_DISCOVERY,
    TARGET_DISCOVERY,
    ENTITLEMENT_DISCOVERY,
    ACCOUNT_STATE_READ,
    ACCOUNT_CREATE,
    ACCOUNT_ENABLE,
    ACCOUNT_DISABLE,
    ACCOUNT_UNLOCK,
    ACCOUNT_DELETE,
    PASSWORD_CHANGE,
    PASSWORD_RESET,
    PASSWORD_ROTATION,
    GROUP_MANAGEMENT,
    PRIVILEGE_MANAGEMENT,
    OBJECT_MOVE,
    DESIRED_STATE,
    RECONCILIATION,
    SESSION_ACCESS,
    COMMAND_EXECUTION,
    FILE_TRANSFER,
    AUDIT
}
