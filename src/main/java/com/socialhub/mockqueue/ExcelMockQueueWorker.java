package com.socialhub.mockqueue;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(name="socialhub.mock-queue.enabled",havingValue="true")
public class ExcelMockQueueWorker {
    private final ExcelMockRunService service;
    private final long delay;
    public ExcelMockQueueWorker(ExcelMockRunService service,
            @Value("${socialhub.mock-queue.demo-delay-ms:${SOCIALHUB_MOCK_DEMO_DELAY_MS:150}}") long delay) {
        if(delay<0 || delay>5000) throw new IllegalArgumentException("Mock delay must be 0..5000 ms");
        this.service=service;this.delay=delay;
    }
    @RabbitListener(queues=ExcelMockQueueConfig.QUEUE,concurrency="1-4")
    public void onMessage(String payload) {service.consume(payload,delay);}
    @PostConstruct
    public void initialized() {
        log.info(
                "[EXCEL MOCK] Worker Bean initialized successfully"
        );
    }
}
