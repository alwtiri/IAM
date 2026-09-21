package com.enterprise.iam.core.audit.api;

import java.time.Instant;

/**
 * Outcome of recomputing the hash chain (ADR-0008).
 *
 * @param valid          true if every event's hash and link matches
 * @param eventsChecked  number of events verified
 * @param firstBrokenSeq sequence number of the first mismatch, or null
 * @param problem        description of the first mismatch, or null
 */
public record AuditVerificationResult(String chainPartition, boolean valid, long eventsChecked, Long firstBrokenSeq,
                                      String problem, Instant verifiedAt) {
}
