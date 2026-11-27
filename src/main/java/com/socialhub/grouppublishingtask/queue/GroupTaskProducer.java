package com.socialhub.grouppublishingtask.queue;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;

/** Diagnostic producer only. No publishing-task route or database mutation. */
@Service
public class GroupTaskProducer {
    public String diagnosticRoute(GroupTaskMessage message) {
        return "dispatch-test." + Objects.requireNonNull(message, "message").messageId();
    }

    public void sendDiagnostic(Channel channel, GroupTaskMessage message) throws IOException {
        Objects.requireNonNull(channel, "channel");
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(GroupTaskMessage.CONTENT_TYPE)
                .type(GroupTaskMessage.TYPE)
                .messageId(message.messageId().toString())
                .correlationId(message.messageId().toString())
                .build();
        channel.basicPublish(GroupRabbitMQConfig.EXCHANGE, diagnosticRoute(message),
                properties, message.encode());
    }
}
