package com.socialhub.grouppublishingtask.queue;

import tools.jackson.databind.annotation.JsonSerialize;
import com.socialhub.response.ViDisplayTextSerializer;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import com.socialhub.grouppublishingtask.queue.GroupRabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Explicit, bounded transport probe. Never sends real task messages or calls a publisher.
 */
@Service
public class GroupQueueTransportProbe {
    private final ObjectProvider<RabbitTemplate> templates;
    private final boolean enabled;
    private final Semaphore singleProbe = new Semaphore(1);

    public record Report(boolean passed, String state, String exchange, String queue,
                         Integer queuedMessages, Integer consumers,
                         @JsonSerialize(using = ViDisplayTextSerializer.class) String note) {
    }

    public GroupQueueTransportProbe(ObjectProvider<RabbitTemplate> templates,
                                    @Value("${app.group.queue.probe-enabled:false}") boolean enabled) {
        this.templates = templates;
        this.enabled = enabled;
    }

    public Report check() {
        if (!enabled) return report(false, "DISABLED", null, null,
                "Enable APP_GROUP_QUEUE_PROBE_ENABLED=true and restart Backend to test transport.");
        if (!singleProbe.tryAcquire()) return report(false, "BUSY", null, null,
                "A probe is already in progress.");
        try {
            RabbitTemplate template = templates.getIfUnique();
            if (template == null) return report(false, "NO_UNIQUE_RABBIT_TEMPLATE", null, null,
                    "No unique RabbitTemplate bean; check the current AMQP configuration.");
            Report result = template.execute(channel -> {
                // Durable topology reserved for future authorized work. No task is sent here.
                channel.exchangeDeclare(GroupRabbitMQConfig.EXCHANGE, "direct", true);
                AMQP.Queue.DeclareOk work = channel.queueDeclare(GroupRabbitMQConfig.TASK_QUEUE,
                        true, false, false, null);
                channel.queueBind(GroupRabbitMQConfig.TASK_QUEUE, GroupRabbitMQConfig.EXCHANGE,
                        GroupRabbitMQConfig.TASK_ROUTE);
                // A private, broker-named, exclusive, auto-delete queue prevents interference
                // with work queues or concurrent probes. The random route cannot match 'task'.
                String probeQueue = channel.queueDeclare().getQueue();
                try {
                    String nonce = UUID.randomUUID().toString();
                    String route = "probe." + nonce;
                    byte[] body = nonce.getBytes(StandardCharsets.UTF_8);
                    channel.queueBind(probeQueue, GroupRabbitMQConfig.EXCHANGE, route);
                    channel.basicPublish(GroupRabbitMQConfig.EXCHANGE, route,
                            new AMQP.BasicProperties.Builder().contentType("text/plain")
                                    .correlationId(nonce).build(), body);
                    // Bounded polling; no persistent listener, no change to global startup flags.
                    GetResponse received = null;
                    for (int i = 0; i < 20 && received == null; i++) {
                        received = channel.basicGet(probeQueue, false);
                        if (received == null) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return report(false, "INTERRUPTED", work.getMessageCount(),
                                        work.getConsumerCount(), "Probe interrupted.");
                            }
                        }
                    }
                    if (received == null) return report(false, "NO_PROBE_MESSAGE", work.getMessageCount(),
                            work.getConsumerCount(), "Broker did not return the test message.");
                    boolean match = Arrays.equals(body, received.getBody()) &&
                            nonce.equals(received.getProps().getCorrelationId());
                    channel.basicAck(received.getEnvelope().getDeliveryTag(), false);
                    return report(match, match ? "TRANSPORT_OK" : "PROBE_MISMATCH",
                            work.getMessageCount(), work.getConsumerCount(),
                            match ? "Test message received and acknowledged. No Group Task was dispatched."
                                    : "Unexpected probe message; no real task was dispatched.");
                } finally {
                    // Also unbinds the temporary route. Exclusive queue is a fallback on connection close.
                    channel.queueDelete(probeQueue);
                }
            });
            return result == null ? report(false, "EMPTY_RESULT", null, null, "No broker result.") : result;
        } catch (RuntimeException ex) {
            return report(false, "BROKER_ERROR", null, null,
                    "Transport probe failed: " + ex.getClass().getSimpleName());
        } finally {
            singleProbe.release();
        }
    }

    private static Report report(boolean passed, String state, Integer messages, Integer consumers, String note) {
        return new Report(passed, state, GroupRabbitMQConfig.EXCHANGE,
                GroupRabbitMQConfig.TASK_QUEUE, messages, consumers, note);
    }
}
