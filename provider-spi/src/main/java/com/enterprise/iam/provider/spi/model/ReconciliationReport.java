package com.enterprise.iam.provider.spi.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Read-only comparison of desired vs actual state. Remediation is decided by the Core (policy/approval). */
public record ReconciliationReport(Instant observedAt, List<Drift> drifts) {
    public ReconciliationReport {
        Objects.requireNonNull(observedAt, "observedAt");
        drifts = drifts == null ? List.of() : List.copyOf(drifts);
    }
}
