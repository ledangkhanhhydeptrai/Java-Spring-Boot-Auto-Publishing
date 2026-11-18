package com.socialhub.automation.ratelimit;

import com.socialhub.automation.ratelimit.PublishingRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublishingRateLimitServiceTest {
    private JdbcTemplate db;
    private PublishingRateLimitService service;

    @BeforeEach void setUp() {
        db = mock(JdbcTemplate.class);
        service = new PublishingRateLimitService(db);
        ReflectionTestUtils.setField(service, "maxPerMinute", 1);
    }

    @Test void missingTokenMustNotConsumeBudget() {
        UUID id = UUID.randomUUID();
        when(db.queryForObject(anyString(), eq(Boolean.class), eq(id))).thenReturn(false);
        assertFalse(service.tryAcquire(id));
        verify(db, never()).query(contains("INSERT INTO automation_publish_budgets"),
            any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any());
    }

    @Test void misconfiguredQuotaFailsClosed() {
        ReflectionTestUtils.setField(service, "maxPerMinute", 0);
        assertThrows(IllegalStateException.class, () -> service.tryAcquire(UUID.randomUUID()));
        verifyNoInteractions(db);
    }
}
