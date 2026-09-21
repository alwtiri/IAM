package com.enterprise.iam.core.shared.api.tx;

import java.util.function.Supplier;

/**
 * Transaction boundary port. Application services run each use case, including its audit event and outbox rows,
 * in exactly one transaction (SEC7, ADR-0006). Production implementation: Spring {@code TransactionTemplate}.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /** Read-only transaction (may be routed to a replica later). */
    <T> T readOnly(Supplier<T> work);
}
