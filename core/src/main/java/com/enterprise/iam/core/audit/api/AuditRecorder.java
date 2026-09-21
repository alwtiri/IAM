package com.enterprise.iam.core.audit.api;

import com.enterprise.iam.core.shared.api.security.CurrentActor;

/**
 * Records audit events inside the caller's transaction (SEC7, ADR-0008). If recording fails, the exception
 * propagates and the business transaction rolls back — privileged changes never happen unaudited.
 */
public interface AuditRecorder {

    /** Records an action performed by {@code actor} (null for anonymous, e.g. failed authentication). */
    void record(CurrentActor actor, AuditEntry entry);
}
