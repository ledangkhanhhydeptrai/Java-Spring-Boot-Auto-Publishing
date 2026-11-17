package com.socialhub.automation.queue;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AutomationQueueConfiguration {
    public static final String EXCHANGE = "socialhub.automation.v1";
    public static final String QUEUE = "socialhub.automation.observe.v1";
    public static final String KEY = "publishing.observe";
    public static final String FACEBOOK_LIVE_QUEUE = "socialhub.automation.facebook.live.v1";
    public static final String THREADS_LIVE_QUEUE = "socialhub.automation.threads.live.v1";
    public static final String FACEBOOK_LIVE_KEY = "publishing.facebook.live";
    public static final String THREADS_LIVE_KEY = "publishing.threads.live";

    @Bean
    DirectExchange automationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue automationObserveQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    Queue automationFacebookLiveQueue() {
        return new Queue(FACEBOOK_LIVE_QUEUE, true);
    }

    @Bean
    Queue automationThreadsLiveQueue() {
        return new Queue(THREADS_LIVE_QUEUE, true);
    }

    @Bean
    Binding automationFacebookLiveBinding(Queue automationFacebookLiveQueue, DirectExchange automationExchange) {
        return BindingBuilder.bind(automationFacebookLiveQueue).to(automationExchange).with(FACEBOOK_LIVE_KEY);
    }

    @Bean
    Binding automationThreadsLiveBinding(Queue automationThreadsLiveQueue, DirectExchange automationExchange) {
        return BindingBuilder.bind(automationThreadsLiveQueue).to(automationExchange).with(THREADS_LIVE_KEY);
    }

    @Bean
    Binding automationObserveBinding(Queue automationObserveQueue, DirectExchange automationExchange) {
        return BindingBuilder.bind(automationObserveQueue).to(automationExchange).with(KEY);
    }
}
