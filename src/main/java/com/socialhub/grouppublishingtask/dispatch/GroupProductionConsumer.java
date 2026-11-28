package com.socialhub.grouppublishingtask.dispatch;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

import java.util.Objects;

/**
 * Listener implementation only. Must NOT be registered until authorized integration and tests pass.
 */
final class GroupProductionConsumer implements MessageListener {
    private final GroupProductionExecutionStore store;
    private final GroupProductionPublisher publisher;

    GroupProductionConsumer(GroupProductionExecutionStore store, GroupProductionPublisher publisher) {
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public void onMessage(Message incoming) {
        final GroupDispatchEnvelope event;
        try {
            event = GroupDispatchEnvelope.decode(incoming.getBody());
            var props = incoming.getMessageProperties();
            if (!GroupDispatchEnvelope.TYPE.equals(props.getType())
                    || !GroupDispatchEnvelope.CONTENT_TYPE.equals(props.getContentType())
                    || !event.eventId().toString().equals(props.getMessageId())
                    || !GroupProductionBroker.EXCHANGE.equals(props.getReceivedExchange())
                    || !GroupProductionBroker.ROUTE.equals(props.getReceivedRoutingKey()))
                throw new IllegalArgumentException("Envelope mismatch");
        } catch (RuntimeException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid production envelope", invalid);
        }
        var reservation = store.reserve(event);
        if (reservation.decision() == GroupProductionExecutionStore.Decision.DUPLICATE) return;
        if (reservation.decision() == GroupProductionExecutionStore.Decision.REJECT)
            throw new AmqpRejectAndDontRequeueException("No matching eligible outbox task");
        String state = "NEEDS_REVIEW", code = "PROVIDER_OUTCOME_UNKNOWN", link = null, remoteId = null;
        try {
            if (!publisher.isAuthorized(reservation.request())) code = "PROVIDER_NOT_AUTHORIZED";
            else {
                var result = publisher.publish(reservation.request());
                if (result != null && result.outcome() == GroupProductionPublisher.Outcome.PUBLISHED
                        && GroupPublicationLink.matches(reservation.request().externalGroupId(), result.permalink())) {
                    state = "PUBLISHED";
                    link = result.permalink();
                    remoteId = result.remotePostId();
                    code = "PROVIDER_CONFIRMED";
                } else if (result != null && result.outcome() == GroupProductionPublisher.Outcome.FAILED) {
                    state = "FAILED";
                    code = "PROVIDER_DEFINITIVE_FAILURE";
                }
            }
        } catch (RuntimeException failure) { /* uncertain remote outcome: never auto-republish */ }
        // If this DB write fails, redelivery cannot call publisher again; expired STARTED needs reconciliation.
        // A false return means a reviewer already fenced this outcome; do not overwrite it.
        store.finish(event, reservation.token(), state, link, code, remoteId);
    }
}
