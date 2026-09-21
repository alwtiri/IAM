package com.enterprise.iam.core.operation.infrastructure.config;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.operation.application.OperationQueryService;
import com.enterprise.iam.core.operation.application.OutboxRelay;
import com.enterprise.iam.core.operation.application.OutboxService;
import com.enterprise.iam.core.operation.application.OutboxStore;
import com.enterprise.iam.core.operation.domain.RetryPolicy;
import com.enterprise.iam.core.operation.infrastructure.messaging.AmqpDispatcher;
import com.enterprise.iam.core.operation.infrastructure.messaging.RabbitHealthCheck;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOperationReader;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOutboxStore;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
