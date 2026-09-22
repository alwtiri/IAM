package com.enterprise.iam.core.operation.infrastructure.config;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.operation.api.OutboxPublisher;
import com.enterprise.iam.core.operation.application.OperationQueryService;
import com.enterprise.iam.core.operation.application.OperationService;
import com.enterprise.iam.core.operation.application.OperationStore;
import com.enterprise.iam.core.operation.application.OutboxRelay;
import com.enterprise.iam.core.operation.application.OutboxService;
import com.enterprise.iam.core.operation.application.OutboxStore;
import com.enterprise.iam.core.operation.domain.RetryPolicy;
import com.enterprise.iam.core.operation.infrastructure.messaging.AmqpDispatcher;
import com.enterprise.iam.core.operation.infrastructure.messaging.OperationResultListener;
import com.enterprise.iam.core.operation.infrastructure.messaging.RabbitHealthCheck;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOperationReader;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOperationStore;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOutboxStore;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class OperationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OperationConfiguration.class);

    @Bean
    OutboxStore outboxStore(JdbcClient jdbc) {
        return new JdbcOutboxStore(jdbc);
    }

    @Bean
    OutboxService outboxService(OutboxStore store, RequestContextProvider ctx, Clock clock) {
        return new OutboxService(store, ctx, clock);
    }

    @Bean
    OperationQueryService operationQueryService(JdbcClient jdbc, AccessGuard guard, TransactionRunner tx) {
        return new OperationQueryService(new JdbcOperationReader(jdbc), guard, tx);
    }

    /** Exchanges used from Phase 2 (events) and Phase 3 (operations). Declared lazily on first connection. */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange("iam.events", true, false);
    }

    @Bean
    TopicExchange operationsExchange() {
        return new TopicExchange("iam.ops", true, false);
    }

    @Bean
    TopicExchange operationResultsExchange() {
        return new TopicExchange("iam.ops.results", true, false);
    }

    // ---------------------------------------------------------------- Phase 3: worker plane (WORKER-ISOLATION.md)

    /** Dead-letter exchange + queue for operations and results that cannot be processed. */
    @Bean
    TopicExchange operationsDeadLetterExchange() {
        return new TopicExchange("iam.ops.dlx", true, false);
    }

    @Bean
    Queue operationsDeadLetterQueue() {
        return QueueBuilder.durable("ops.dlq").build();
    }

    @Bean
    Binding operationsDeadLetterBinding() {
        return BindingBuilder.bind(operationsDeadLetterQueue()).to(operationsDeadLetterExchange()).with("#");
    }

    /**
     * One durable queue per provider type (G4). Declared by the Core as well, so commands wait for workers that are not
     * running yet. Arguments must stay identical to the worker's declaration.
     */
    @Bean
    Declarables providerQueues(@Value("${iam.operations.provider-types:linux-ssh,active-directory,windows-winrm,generic-rest}") List<String> types) {
        List<Declarable> out = new ArrayList<>();
        for (String type : types) {
            Queue q = QueueBuilder.durable("ops." + type).deadLetterExchange("iam.ops.dlx").maxLength(100_000).overflow(QueueBuilder.Overflow.rejectPublish).build();
            out.add(q);
            out.add(BindingBuilder.bind(q).to(operationsExchange()).with("ops." + type));
        }
        return new Declarables(out);
    }

    @Bean
    Queue operationResultQueue(@Value("${iam.operations.result-queue:iam.core.results}") String name) {
        return QueueBuilder.durable(name).deadLetterExchange("iam.ops.dlx").build();
    }

    @Bean
    Binding operationResultBinding(Queue operationResultQueue) {
        return BindingBuilder.bind(operationResultQueue).to(operationResultsExchange()).with("#");
    }

    @Bean
    OperationStore operationStore(JdbcClient jdbc) {
        return new JdbcOperationStore(jdbc);
    }

    @Bean
    OperationService operationService(OperationStore store, OutboxPublisher outbox, DomainEventPublisher events, AuditRecorder audit,
                                      RequestContextProvider ctx, TransactionRunner tx, Clock clock) {
        return new OperationService(store, outbox, events, audit, ctx, tx, clock);
    }

    @Bean
    OperationResultListener operationResultListener(OperationService operations) {
        return new OperationResultListener(operations);
    }

    @Bean
    OperationDeadlineJob operationDeadlineJob(OperationService operations) {
        return new OperationDeadlineJob(operations);
    }

    /** Times out operations whose deadline passed without a result (no silent loss). */
    static class OperationDeadlineJob {
        private final OperationService operations;

        OperationDeadlineJob(OperationService operations) {
            this.operations = operations;
        }

        @Scheduled(fixedDelayString = "${iam.operations.deadline-sweep-interval:PT15S}")
        void run() {
            try {
                int n = operations.sweepOverdue(100);
                if (n > 0) {
                    log.info("Timed out {} overdue operation(s)", n);
                }
            } catch (RuntimeException e) {
                log.warn("Operation deadline sweep failed; will retry", e);
            }
        }
    }

    @Bean
    AmqpDispatcher amqpDispatcher(RabbitTemplate rabbit) {
        return new AmqpDispatcher(rabbit, 5_000);
    }

    @Bean
    RabbitHealthCheck rabbitHealthCheck(ConnectionFactory cf, OutboxStore store,
                                        @Value("${iam.outbox.backlog-warning:1000}") long backlogWarning) {
        return new RabbitHealthCheck(cf, store, backlogWarning);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, List<MessageDispatcher> dispatchers, TransactionRunner tx, Clock clock) {
        return new OutboxRelay(store, dispatchers, tx, RetryPolicy.DEFAULT, clock, Duration.ofSeconds(60), 50);
    }

    @Bean
    OutboxRelayJob outboxRelayJob(OutboxRelay relay) {
        return new OutboxRelayJob(relay);
    }

    /** Runs the relay; safe on multiple Core replicas thanks to SKIP LOCKED claims. */
    static class OutboxRelayJob {
        private final OutboxRelay relay;

        OutboxRelayJob(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${iam.outbox.relay-interval:PT2S}")
        void run() {
            try {
                OutboxRelay.Pass pass = relay.relayOnce();
                if (pass.claimed() > 0) {
                    log.debug("Outbox relay pass: {}", pass);
                }
            } catch (RuntimeException e) {
                log.warn("Outbox relay pass failed; will retry", e);
            }
        }
    }
}
