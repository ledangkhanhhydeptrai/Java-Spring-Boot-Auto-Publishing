package com.socialhub.mockqueue;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "socialhub.mock-queue.enabled", havingValue = "true")
public class MockQueueConfiguration {
    public static final String QUEUE = "socialhub.mock.synthetic.v1";
    @Bean public Queue syntheticMockQueue() { return new Queue(QUEUE, true, false, false); }
    @Bean public MockJobEngine mockJobEngine() { return new MockJobEngine(); }
}
