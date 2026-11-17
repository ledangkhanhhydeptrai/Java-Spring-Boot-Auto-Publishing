package com.socialhub.automation.live;

import com.socialhub.publishingjob.engine.FanpageQueueProcessor;
import com.socialhub.threads.ThreadsPublishingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AutomationExactJobExecutorTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final FanpageQueueProcessor facebook = mock(FanpageQueueProcessor.class);
    private final ThreadsPublishingService threads = mock(ThreadsPublishingService.class);
    private final AutomationExactJobExecutor executor =
            new AutomationExactJobExecutor(db, facebook, threads);

    @Test
    void liveIsDisabledByDefault() {
        var error = assertThrows(IllegalStateException.class,
                () -> executor.execute(UUID.randomUUID()));
        assertTrue(error.getMessage().contains("disabled"));
        verifyNoInteractions(db, facebook, threads);
    }
}
