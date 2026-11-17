
package com.socialhub.publishingjob.service.implement;

import com.socialhub.publishingjob.engine.FanpageQueueProcessor;
import com.socialhub.threads.ThreadsPublishingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class PublisherSchedulerSafetyTest {

    private FanpageQueueProcessor facebook;
    private ThreadsPublishingService threads;
    private PublishingJobRecoveryService recovery;

    private PublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        facebook = mock(FanpageQueueProcessor.class);
        threads = mock(ThreadsPublishingService.class);
        recovery = mock(PublishingJobRecoveryService.class);

        scheduler = new PublisherScheduler(
                facebook,
                recovery,
                threads
        );

        ReflectionTestUtils.setField(
                scheduler,
                "facebookEnabled",
                false
        );

        ReflectionTestUtils.setField(
                scheduler,
                "threadsEnabled",
                false
        );

        ReflectionTestUtils.setField(
                scheduler,
                "publishingMode",
                "MOCK"
        );

        ReflectionTestUtils.setField(
                scheduler,
                "bulkEnabled",
                false
        );
    }

    @Test
    void shouldNotExecuteFacebookWorkerWhenDisabled() {
        scheduler.tick();

        verify(facebook, never()).processBatch();
        verifyNoInteractions(recovery);
    }

    @Test
    void shouldNotExecuteThreadsWorkerWhenDisabled() {
        scheduler.tick();

        verify(threads, never()).processDueBatch();
    }

    @Test
    void shouldNotExecuteAnyWorkerWhenDisabled() {
        scheduler.tick();

        verifyNoInteractions(
                facebook,
                threads,
                recovery
        );
    }
}
