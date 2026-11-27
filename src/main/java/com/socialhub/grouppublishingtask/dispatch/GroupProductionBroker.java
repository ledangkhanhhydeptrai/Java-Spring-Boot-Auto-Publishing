package com.socialhub.grouppublishingtask.dispatch;

import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Explicitly constructed by future authorized runtime. No Spring bean or topology declaration at boot. */
final class GroupProductionBroker {
    static final String EXCHANGE = "socialhub.group.dispatch.v1";
    static final String ROUTE = "task";
    static final String QUEUE = "socialhub.group.dispatch.tasks.v1";
    static final String DEAD_EXCHANGE = "socialhub.group.dispatch.dead.v1";
    static final String DEAD_QUEUE = "socialhub.group.dispatch.dead.tasks.v1";
    enum Delivery { CONFIRMED, UNROUTABLE, NACK, UNKNOWN }
    private final RabbitTemplate template;
    private final RabbitAdmin admin;
    GroupProductionBroker(CachingConnectionFactory factory) {
        if (!factory.isPublisherConfirms() || !factory.isPublisherReturns())
            throw new IllegalStateException("Correlated publisher confirms and returns required");
        // Dedicated template: never change shared Fanpage/Threads RabbitTemplate properties.
        template = new RabbitTemplate(factory);
        template.setMandatory(true);
        admin = new RabbitAdmin(factory);
        admin.setAutoStartup(false);
    }
    void declareTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(ROUTE).build();
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();
        admin.declareExchange(exchange); admin.declareExchange(dead);
        admin.declareQueue(queue); admin.declareQueue(deadQueue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTE));
        admin.declareBinding(BindingBuilder.bind(deadQueue).to(dead).with(ROUTE));
    }
    Delivery send(GroupDispatchEnvelope event) {
        // Attempt correlation differs each time; business message ID never changes.
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        var props = new MessageProperties();
        props.setContentType(GroupDispatchEnvelope.CONTENT_TYPE);
        props.setType(GroupDispatchEnvelope.TYPE);
        props.setMessageId(event.eventId().toString());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            template.send(EXCHANGE, ROUTE, new Message(event.encode(), props), correlation);
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (correlation.getReturned() != null) return Delivery.UNROUTABLE;
            return confirm.ack() ? Delivery.CONFIRMED : Delivery.NACK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Delivery.UNKNOWN;
        } catch (Exception failed) { return Delivery.UNKNOWN; }
    }
}
