package com.enterprise.iam.core.operation.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Operation lifecycle (DOMAIN-MODEL §9). SUCCESS is only reachable with a verification record. */
public enum OperationStatus {
    QUEUED, RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED, PARTIAL, UNKNOWN;

    private static final Map<OperationStatus, Set<OperationStatus>> ALLOWED = Map.of(
            QUEUED, EnumSet.of(RUNNING, CANCELLED, TIMEOUT),
            RUNNING, EnumSet.of(SUCCESS, FAILED, PARTIAL, TIMEOUT, UNKNOWN),
            UNKNOWN, EnumSet.of(SUCCESS, FAILED),
            PARTIAL, EnumSet.of(RUNNING),
            FAILED, EnumSet.of(QUEUED),
            TIMEOUT, EnumSet.of(QUEUED),
            SUCCESS, EnumSet.noneOf(OperationStatus.class),
            CANCELLED, EnumSet.noneOf(OperationStatus.class));

    public boolean canTransitionTo(OperationStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == CANCELLED;
    }
}
