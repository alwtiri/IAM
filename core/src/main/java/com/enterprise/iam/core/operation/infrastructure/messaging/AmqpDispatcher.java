package com.enterprise.iam.core.operation.infrastructure.messaging;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.kernel.Json;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes {@code amqp:<exchange>/<routingKey>} outbox messages with persistent delivery and waits for the broker's
 * publisher confirm; a missing confirm is a failure and the relay retries (at-least-once, de-duplicated by messageId).
 */
public class AmqpDispatcher implements MessageDispatcher {

    private final RabbitTemplate rabbit;
    private final long confirmTimeoutMillis;

    public AmqpDispatcher(RabbitTemplate rabbit, long confirmTimeoutMillis) {
        this.rabbit = rabbit;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Override
    public String kind() {
        return "amqp";
    }

    @Override
    public void dispatch(OutboxMessage m) {
        String address = m.address();
        int slash = address.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Invalid AMQP destination " + m.destination());
        }
        String exchange = address.substring(0, slash);
        String routingKey = address.substring(slash + 1);
        MessageProperties props = new MessageProperties();
        props.setMessageId(m.id().toString());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        m.headers().forEach(props::setHeader);
        Message message = new Message(Json.write(m.payload()).getBytes(StandardCharsets.UTF_8), props);
        rabbit.invoke(ops -> {
            ops.send(exchange, routingKey, message);
            ops.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }
}
