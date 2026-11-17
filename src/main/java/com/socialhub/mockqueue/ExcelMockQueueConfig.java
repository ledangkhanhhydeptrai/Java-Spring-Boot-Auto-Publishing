package com.socialhub.mockqueue;

import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "socialhub.mock-queue.enabled", havingValue = "true")
public class ExcelMockQueueConfig {
    // Different queue from the synthetic load test and ALL LIVE queues.
    public static final String QUEUE = "socialhub.mock.excel.v1";
    @Bean public Queue excelMockQueue() { return new Queue(QUEUE, true, false, false); }
}
