package com.socialhub.grouppublishingtask.queue;

import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService;
import com.socialhub.grouppublishingtask.automation.GroupTaskQueueService.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Scheduled diagnostic only: separate route and queue, no real task IDs or publishing calls.
 */
@Component
public class GroupBackgroundDispatch {
    public static final String QUEUE = "socialhub.group.automation.diagnostic.background.v1";
    public static final String ROUTE = "diagnostic.background.v1";
    private static final Logger log = LoggerFactory.getLogger(GroupBackgroundDispatch.class);
    private final ObjectProvider<RabbitTemplate> templates;
    private final boolean enabled;
    private final GroupTaskQueueService tasks;
    private final ConcurrentMap<Target, Cycle> last = new ConcurrentHashMap<>();
    private static final int TARGETS_PER_TICK = 5;
    private static final long CACHE_TTL_SECONDS = 3600;
    private UUID cursor;

    public record Cycle(UUID messageId, Instant sentAt, Instant receivedAt, String state) {
    }

    public record Status(boolean enabled, boolean configuredForGroup, String queue, String state,
                         UUID messageId, Instant sentAt, Instant receivedAt) {
    }

    public GroupBackgroundDispatch(ObjectProvider<RabbitTemplate> templates,
                                   GroupTaskQueueService tasks,
                                   @Value("${app.group.queue.background-test-enabled:false}") boolean enabled) {
        this.templates = templates;
        this.tasks = tasks;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.group.queue.background-initial-delay-ms:30000}",
            fixedDelayString = "${app.group.queue.background-poll-ms:120000}")
    public void tick() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minusSeconds(CACHE_TTL_SECONDS);
        last.entrySet().removeIf(entry -> entry.getValue().sentAt().isBefore(cutoff));
        try {
            var targets = tasks.activeTargetsAfter(cursor, TARGETS_PER_TICK);
            if (targets.isEmpty() && cursor != null) {
                cursor = null;
                targets = tasks.activeTargetsAfter(null, TARGETS_PER_TICK);
            }
            for (Target target : targets) {
                cursor = target.groupId();
                sendDiagnostic(target);
            }
        } catch (RuntimeException ex) {
            log.warn("[GROUP BACKGROUND TEST] Discovery stopped: {}", ex.getClass().getSimpleName());
        }
    }

    private void sendDiagnostic(Target target) {
        Cycle previous = last.get(target);
        if (previous != null && "SENT_WAITING_FOR_LISTENER".equals(previous.state())
                && previous.sentAt().plusSeconds(120).isAfter(Instant.now())) return;
        GroupTaskMessage message = GroupTaskMessage.diagnostic(target.workspaceId(), target.groupId());
        Cycle cycle = new Cycle(message.messageId(), Instant.now(), null, "SENT_WAITING_FOR_LISTENER");
        last.put(target, cycle);
        try {
            RabbitTemplate template = templates.getIfUnique();
            if (template == null) throw new IllegalStateException("No unique RabbitTemplate");
            template.execute(channel -> {
                // Only the diagnostic queue; no declarations or consuming of live task queue.
                channel.exchangeDeclare(GroupRabbitMQConfig.EXCHANGE, "direct", true);
                channel.queueDeclare(QUEUE, true, false, false,
                        java.util.Map.of("x-message-ttl", 120000, "x-max-length", 20));
                channel.queueBind(QUEUE, GroupRabbitMQConfig.EXCHANGE, ROUTE);
                var props = new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .contentType(GroupTaskMessage.CONTENT_TYPE).type(GroupTaskMessage.TYPE)
                        .messageId(message.messageId().toString())
                        .correlationId(message.messageId().toString()).build();
                channel.basicPublish(GroupRabbitMQConfig.EXCHANGE, ROUTE, props, message.encode());
                return Boolean.TRUE;
            });
            log.info("[GROUP BACKGROUND TEST] sent message={}", message.messageId());
        } catch (RuntimeException ex) {
            last.replace(target, cycle, new Cycle(cycle.messageId(), cycle.sentAt(), null, "SEND_ERROR"));
            log.warn("[GROUP BACKGROUND TEST] send failed: {}", ex.getClass().getSimpleName());
        }
    }

    @RabbitListener(id = "socialhubGroupBackgroundDiagnosticV1",
            autoStartup = "${app.group.queue.background-test-enabled:false}",
            ackMode = "AUTO", concurrency = "1",
            bindings = @QueueBinding(
                    value = @Queue(value = QUEUE, durable = "true", arguments = {
                            @Argument(name = "x-message-ttl", value = "120000", type = "java.lang.Integer"),
                            @Argument(name = "x-max-length", value = "20", type = "java.lang.Integer")}),
                    exchange = @Exchange(value = GroupRabbitMQConfig.EXCHANGE, type = "direct", durable = "true"),
                    key = ROUTE))
    public void receive(Message incoming) {
        final GroupTaskMessage decoded;
        try {
            decoded = GroupTaskMessage.decode(incoming.getBody());
            var props = incoming.getMessageProperties();
            if (!enabled || !GroupTaskMessage.TYPE.equals(props.getType())
                    || !GroupTaskMessage.CONTENT_TYPE.equals(props.getContentType())
                    || !decoded.messageId().toString().equals(props.getMessageId())
                    || !decoded.messageId().toString().equals(props.getCorrelationId())
                    || !GroupRabbitMQConfig.EXCHANGE.equals(props.getReceivedExchange())
                    || !ROUTE.equals(props.getReceivedRoutingKey())) {
                throw new IllegalArgumentException("Unexpected diagnostic envelope");
            }
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid Group background diagnostic", ex);
        }
        Target target = new Target(decoded.workspaceId(), decoded.facebookGroupId());
        // Match only an outstanding diagnostic generated by this instance for this exact pair.
        Cycle current = last.get(target);
        if (current == null || !decoded.messageId().equals(current.messageId())) {
            throw new AmqpRejectAndDontRequeueException("Unknown or stale Group diagnostic correlation");
        }
        last.computeIfPresent(target, (key, cycle) -> decoded.messageId().equals(cycle.messageId())
                && "SENT_WAITING_FOR_LISTENER".equals(cycle.state())
                ? new Cycle(cycle.messageId(), cycle.sentAt(), Instant.now(), "BACKGROUND_LISTENER_OK") : cycle);
        log.info("[GROUP BACKGROUND TEST] listener validated message={}", decoded.messageId());
        // Spring listener acknowledges successful return. No DB mutation or publication.
    }

    public Status status(UUID requestedWorkspace, UUID requestedGroup) {
        boolean selected = tasks.hasActiveTasks(requestedWorkspace, requestedGroup);
        Cycle cycle = enabled && selected ? last.get(new Target(requestedWorkspace, requestedGroup)) : null;
        String state = !enabled ? "DISABLED" : !selected ? "NO_ACTIVE_GROUP_TASKS" : cycle == null
                ? "WAITING_FOR_FIRST_TICK" : cycle.state();
        if (cycle != null && "SENT_WAITING_FOR_LISTENER".equals(state)
                && cycle.sentAt().plusSeconds(120).isBefore(Instant.now())) {
            state = "LISTENER_TIMEOUT";
        }
        return new Status(enabled, selected, QUEUE, state, cycle == null ? null : cycle.messageId(),
                cycle == null ? null : cycle.sentAt(), cycle == null ? null : cycle.receivedAt());
    }

}
