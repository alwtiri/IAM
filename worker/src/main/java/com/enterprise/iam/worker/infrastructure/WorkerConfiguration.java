package com.enterprise.iam.worker.infrastructure;

import com.enterprise.iam.kernel.Json;
import com.enterprise.iam.worker.runtime.Command;
import com.enterprise.iam.worker.runtime.HandleRedeemer;
import com.enterprise.iam.worker.runtime.InstanceGuards;
import com.enterprise.iam.worker.runtime.MtlsHandleRedeemer;
import com.enterprise.iam.worker.runtime.OperationExecutor;
import com.enterprise.iam.worker.runtime.PemTls;
import com.enterprise.iam.worker.runtime.ProviderRegistry;
import com.enterprise.iam.worker.runtime.ResultSink;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker wiring (WORKER-ISOLATION.md): one durable queue and one listener container per provider type with its own
 * concurrency (G4), per-instance breakers/bulkheads inside the executor, results with publisher confirms.
 * Queue arguments are identical to the Core's declaration (contracts/README.md).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProviderRegistry providerRegistry(WorkerProperties p) {
        ProviderRegistry r = ProviderRegistry.fromClasspath(p.pools());
        log.info("Worker pools {}; provider plugins {}", p.pools(), r.versions());
        for (String type : p.pools()) {
            if (r.find(type).isEmpty()) {
                log.warn("No provider plugin for pool '{}': its operations will be answered UNSUPPORTED", type);
            }
        }
        return r;
    }

    @Bean
    InstanceGuards instanceGuards(Clock clock, WorkerProperties p) {
        return new InstanceGuards(clock, p.bulkhead());
    }

    @Bean
    HandleRedeemer handleRedeemer(WorkerProperties p) throws IOException, GeneralSecurityException {
        return new MtlsHandleRedeemer(p.coreInternalUrl(), PemTls.context(Path.of(p.certFile()), Path.of(p.keyFile()), Path.of(p.caFile())));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService providerCalls() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ResultSink resultSink(RabbitTemplate rabbit) {
        return message -> {
            MessageProperties props = new MessageProperties();
            props.setMessageId(String.valueOf(message.get("messageId")));
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            Message m = new Message(Json.write(message).getBytes(StandardCharsets.UTF_8), props);
            rabbit.invoke(ops -> {
                ops.send("iam.ops.results", "result", m);
                ops.waitForConfirmsOrDie(10_000);
                return null;
            });
        };
    }

    @Bean
    OperationExecutor operationExecutor(ProviderRegistry registry, InstanceGuards guards, HandleRedeemer redeemer, ResultSink sink,
                                        ExecutorService providerCalls, Clock clock, WorkerProperties p) throws IOException {
        String instance = p.instance() != null && !p.instance().isBlank() ? p.instance() : InetAddress.getLocalHost().getHostName();
        return new OperationExecutor(registry, guards, redeemer, sink, providerCalls, clock, instance, p.retryBackoff());
    }

    // ------------------------------------------------------------------ topology (identical to the Core's declaration)

    @Bean
    Declarables workerTopology(WorkerProperties p) {
        List<Declarable> out = new ArrayList<>();
        TopicExchange ops = new TopicExchange("iam.ops", true, false);
        TopicExchange results = new TopicExchange("iam.ops.results", true, false);
        TopicExchange dlx = new TopicExchange("iam.ops.dlx", true, false);
        out.add(ops);
        out.add(results);
        out.add(dlx);
        Queue dlq = QueueBuilder.durable("ops.dlq").build();
        out.add(dlq);
        out.add(BindingBuilder.bind(dlq).to(dlx).with("#"));
        for (String type : p.pools()) {
            Queue q = QueueBuilder.durable("ops." + type).deadLetterExchange("iam.ops.dlx").maxLength(100_000)
                    .overflow(QueueBuilder.Overflow.rejectPublish).build();
            out.add(q);
            out.add(BindingBuilder.bind(q).to(ops).with("ops." + type));
        }
        return new Declarables(out);
    }

    /** One container per pool: a busy or slow provider type cannot consume another type's consumers (G4). */
    @Bean
    PoolContainers poolContainers(ConnectionFactory cf, OperationExecutor executor, WorkerProperties p) {
        List<SimpleMessageListenerContainer> containers = new ArrayList<>();
        for (String type : p.pools()) {
            SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
            c.setQueueNames("ops." + type);
            c.setConcurrentConsumers(p.concurrencyOf(type));
            c.setMaxConcurrentConsumers(p.concurrencyOf(type));
            c.setPrefetchCount(1);
            c.setAcknowledgeMode(AcknowledgeMode.AUTO);
            c.setDefaultRequeueRejected(true);
            c.setMissingQueuesFatal(false);
            c.setMessageListener(message -> {
                Command command;
                try {
                    command = Command.parse(new String(message.getBody(), StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    log.warn("Rejecting malformed command {} on ops.{}: {}", message.getMessageProperties().getMessageId(), type, e.getMessage());
                    throw new AmqpRejectAndDontRequeueException("malformed command", e);
                }
                if (!type.equals(command.providerType())) {
                    throw new AmqpRejectAndDontRequeueException("command for " + command.providerType() + " on queue ops." + type);
                }
                executor.execute(command);
            });
            containers.add(c);
        }
        return new PoolContainers(containers);
    }

    /** Starts the pool containers after the context is ready and stops them on shutdown (in-flight operations finish). */
    static final class PoolContainers implements SmartLifecycle {
        private final List<SimpleMessageListenerContainer> containers;
        private volatile boolean running;

        PoolContainers(List<SimpleMessageListenerContainer> containers) {
            this.containers = containers;
        }

        @Override
        public void start() {
            containers.forEach(c -> {
                c.afterPropertiesSet();
                c.start();
            });
            running = true;
        }

        @Override
        public void stop() {
            containers.forEach(SimpleMessageListenerContainer::stop);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
