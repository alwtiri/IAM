package com.enterprise.iam.core.operation.infrastructure.messaging;

import com.enterprise.iam.core.operation.application.OutboxStore;
import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import java.util.List;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/** RabbitMQ reachability plus outbox backlog (spec §62). */
public class RabbitHealthCheck implements ComponentHealthCheck {

    private final ConnectionFactory connections;
    private final OutboxStore outbox;
    private final long backlogWarning;

    public RabbitHealthCheck(ConnectionFactory connections, OutboxStore outbox, long backlogWarning) {
        this.connections = connections;
        this.outbox = outbox;
        this.backlogWarning = backlogWarning;
    }

    @Override
    public String component() {
        return "rabbitmq";
    }

    @Override
    public ComponentHealth.Category category() {
        return ComponentHealth.Category.MESSAGE_BROKER;
    }

    @Override
    public ComponentHealth.Classification classification() {
        return ComponentHealth.Classification.CORE_DEPENDENCY;
    }

    @Override
    public List<String> affectedFunctionality() {
        return List.of("Asynchronous delivery of events and provider operations is delayed (queued safely in the outbox)");
    }

    @Override
    public Result check() {
        try (Connection c = connections.createConnection()) {
            if (!c.isOpen()) {
                return Result.unavailable("connection not open");
            }
        }
        long pending = outbox.pendingCount();
        return pending > backlogWarning ? Result.degraded("outbox backlog: " + pending + " pending messages") : Result.healthy();
    }
}
