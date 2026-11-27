package com.socialhub.grouppublishingtask.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import com.socialhub.security.AccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Explicit admin-only producer/consumer diagnostic, NOT a background publishing listener.
 * Uses an exclusive temporary queue and never reads the durable Group Task Queue.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/group-automation/queue")
public class GroupTaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(GroupTaskConsumer.class);
    private final AccessService access;
    private final GroupTaskQueueService tasks;
    private final GroupTaskProducer producer;
    private final ObjectProvider<RabbitTemplate> templates;
    private final boolean enabled;
    private final Semaphore singleTest = new Semaphore(1);

    public GroupTaskConsumer(AccessService access, GroupTaskQueueService tasks,
                             GroupTaskProducer producer, ObjectProvider<RabbitTemplate> templates,
                             @Value("${app.group.queue.probe-enabled:false}") boolean enabled) {
        this.access = access;
        this.tasks = tasks;
        this.producer = producer;
        this.templates = templates;
        this.enabled = enabled;
    }

    public record Report(boolean passed, String state, UUID messageId,
                         String exchange, String queue, Integer queuedMessages,
                         Integer consumers, String note) {
    }

    @PostMapping("/dispatch-test")
    public Report dispatchTest(@PathVariable UUID workspaceId, @RequestParam UUID facebookGroupId) {
        access.admin(workspaceId);
        tasks.counts(workspaceId, facebookGroupId); // Existing tenant check; no writes.
        if (!enabled) return report(false, "DISABLED", null, null, null, "Diagnostic is disabled.");
        if (!singleTest.tryAcquire()) return report(false, "BUSY", null, null, null, "Another diagnostic is running.");
        GroupTaskMessage expected = GroupTaskMessage.diagnostic(workspaceId, facebookGroupId);
        try {
            RabbitTemplate template = templates.getIfUnique();
            if (template == null) return report(false, "NO_UNIQUE_RABBIT_TEMPLATE", expected.messageId(),
                    null, null, "No unique RabbitTemplate bean.");
            Report result = template.execute(channel -> roundTrip(channel, expected));
            return result == null ? report(false, "EMPTY_RESULT", expected.messageId(), null, null,
                    "No broker result.") : result;
        } catch (RuntimeException ex) {
            log.warn("[GROUP QUEUE DIAGNOSTIC] message={} failed", expected.messageId(), ex);
            return report(false, "BROKER_ERROR", expected.messageId(), null, null,
                    "Diagnostic failed: " + ex.getClass().getSimpleName());
        } finally {
            singleTest.release();
        }
    }

    private Report roundTrip(Channel channel, GroupTaskMessage expected) throws IOException {
        channel.exchangeDeclare(GroupRabbitMQConfig.EXCHANGE, "direct", true);
        // Read broker metadata only. Do not consume or modify the existing task queue.
        var work = channel.queueDeclarePassive(GroupRabbitMQConfig.TASK_QUEUE);
        String temporaryQueue = channel.queueDeclare().getQueue();
        try {
            channel.queueBind(temporaryQueue, GroupRabbitMQConfig.EXCHANGE, producer.diagnosticRoute(expected));
            producer.sendDiagnostic(channel, expected);
            for (int i = 0; i < 20; i++) {
                GetResponse received = channel.basicGet(temporaryQueue, false);
                if (received != null) {
                    boolean valid = matches(received, expected);
                    long tag = received.getEnvelope().getDeliveryTag();
                    if (valid) {
                        channel.basicAck(tag, false);
                        return report(true, "DISPATCH_TEST_OK", expected.messageId(), work.getMessageCount(),
                                work.getConsumerCount(), "Diagnostic envelope validated and acknowledged. No real Task dispatched.");
                    }
                    channel.basicReject(tag, false);
                    return report(false, "MESSAGE_MISMATCH", expected.messageId(), work.getMessageCount(),
                            work.getConsumerCount(), "Unexpected diagnostic envelope rejected.");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return report(false, "INTERRUPTED", expected.messageId(), work.getMessageCount(),
                            work.getConsumerCount(), "Diagnostic interrupted.");
                }
            }
            return report(false, "NO_TEST_MESSAGE", expected.messageId(), work.getMessageCount(),
                    work.getConsumerCount(), "No diagnostic message received.");
        } finally {
            channel.queueDelete(temporaryQueue);
        }
    }

    private boolean matches(GetResponse received, GroupTaskMessage expected) {
        try {
            var properties = received.getProps();
            return GroupTaskMessage.TYPE.equals(properties.getType())
                    && GroupTaskMessage.CONTENT_TYPE.equals(properties.getContentType())
                    && expected.messageId().toString().equals(properties.getMessageId())
                    && expected.messageId().toString().equals(properties.getCorrelationId())
                    && GroupRabbitMQConfig.EXCHANGE.equals(received.getEnvelope().getExchange())
                    && producer.diagnosticRoute(expected).equals(received.getEnvelope().getRoutingKey())
                    && expected.equals(GroupTaskMessage.decode(received.getBody()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static Report report(boolean passed, String state, UUID messageId,
                                 Integer messages, Integer consumers, String note) {
        return new Report(passed, state, messageId, GroupRabbitMQConfig.EXCHANGE,
                GroupRabbitMQConfig.TASK_QUEUE, messages, consumers, note);
    }
}
