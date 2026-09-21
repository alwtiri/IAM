package com.enterprise.iam.core.shared.api.events;

/**
 * Publishes domain events synchronously within the current transaction. Listeners that write data (e.g. the
 * notification outbox) therefore commit or roll back together with the originating change.
 */
@FunctionalInterface
public interface DomainEventPublisher {
    void publish(Object event);
}
