package com.enterprise.iam.core.account.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for vaulted credentials (references and states only) and checkouts. */
public interface CredentialVaultStore {

    record Vaulted(UUID accountId, String secretPath, String currentRef, String pendingRef, String rotationStatus, UUID rotationOperationId,
                   String rotationTrigger, String lastError, Instant lastRotatedAt, Integer rotationIntervalDays, UUID managedBy,
                   Instant createdAt, long version) {
    }

    record Checkout(UUID id, UUID accountId, UUID identityId, UUID requestId, String reason, Instant startedAt, Instant notAfter, String status,
                    Instant endedAt, UUID endedBy, int revealCount, Instant lastRevealedAt) {
    }

    Optional<Vaulted> find(UUID accountId);

    /** Same as {@link #find} with a row lock (serialises rotations of one account). */
    Optional<Vaulted> lock(UUID accountId);

    Optional<Vaulted> findByOperation(UUID operationId);

    List<Vaulted> all();

    void insert(Vaulted v, Instant now);

    boolean update(Vaulted v, Instant now);

    void insertCheckout(Checkout c);

    Optional<Checkout> findCheckout(UUID id);

    Optional<Checkout> activeCheckout(UUID accountId);

    /** Ends an ACTIVE checkout; false if it was no longer active. */
    boolean endCheckout(UUID id, String status, UUID endedBy, Instant at);

    void recordReveal(UUID id, Instant at);

    List<Checkout> checkoutsOf(UUID identityId, int limit);

    List<Checkout> recentCheckouts(int limit);

    List<Checkout> overdue(Instant now);

    /** Review state of a break-glass checkout. */
    record EmergencyReview(UUID checkoutId, String status, UUID reviewedBy, Instant reviewedAt, String note) {
    }

    boolean isEmergency(UUID accountId);

    void setEmergency(UUID accountId, boolean emergency);

    /** Marks a checkout as break-glass (review PENDING). */
    void markEmergencyCheckout(UUID checkoutId);

    List<Checkout> emergencyCheckouts(boolean pendingOnly, int limit);

    Optional<EmergencyReview> emergencyReview(UUID checkoutId);

    boolean reviewEmergency(UUID checkoutId, UUID reviewedBy, String note, Instant at);
}
