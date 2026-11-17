package com.socialhub.campaign.service.implement;

import com.socialhub.campaign.dto.CampaignDto;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Safe service-level tests of the Campaign approval gates.
 * All JDBC operations are mocked: no PostgreSQL connection, Scheduler, or Meta API calls.
 * The real SQL queries still require separate PostgreSQL integration tests.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceImplTest {

    @Mock
    private JdbcTemplate db;
    @Mock
    private AccessService access;

    private CampaignServiceImpl service;
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CampaignServiceImpl(db, access);
    }

    @Test
    void addJob_rejectsPendingJobWhenPostNotApproved() throws Exception {
        stubCampaign("PAUSED");
        stubJob("PENDING", "READY", "NOT_SUBMITTED", "CONNECTED", "FACEBOOK_PAGE");

        ApiException error = assertThrows(ApiException.class,
                () -> service.addJob(campaignId, new CampaignDto.AddJob(jobId)));

        assertTrue(error.getMessage().contains("Post approval or publishing eligibility is missing"));
        verify(db, never()).update(
                contains("INSERT INTO campaign_jobs"),
                eq(campaignId),
                eq(jobId)
        );
    }

    @Test
    void addJob_acceptsPendingJobWhenPostApproved() throws Exception {
        stubCampaign("PAUSED");
        stubJob("PENDING", "READY", "APPROVED", "CONNECTED", "FACEBOOK_PAGE");

        when(db.update(
                contains("INSERT INTO campaign_jobs"),
                eq(campaignId),
                eq(jobId)
        )).thenReturn(1);
        CampaignDto.Progress expected = new CampaignDto.Progress(
                campaignId, "TEST_APPROVAL_CAMPAIGN", "PAUSED",
                1L, 1L, 0L, 0L, 0L, 0L, 0L, 0.0
        );
        when(db.queryForObject(contains("FROM campaign_jobs cj"),
                org.mockito.ArgumentMatchers.<RowMapper<CampaignDto.Progress>>any(), eq(campaignId)))
                .thenReturn(expected);

        CampaignDto.Progress actual = service.addJob(campaignId, new CampaignDto.AddJob(jobId));

        assertEquals(expected, actual);
        verify(db).update(contains("INSERT INTO campaign_jobs"), eq(campaignId), eq(jobId));
    }

    @Test
    void resume_rejectsCampaignWithUnapprovedExecutableJob() throws Exception {
        stubCampaign("PAUSED");
        when(db.queryForObject(contains("FROM campaign_jobs cj"), eq(Long.class),
                eq(campaignId), eq(workspaceId), eq(workspaceId)))
                .thenReturn(1L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.resume(campaignId));

        assertTrue(error.getMessage().contains("without approval"));
        verify(db, never()).update(
                contains("UPDATE campaigns"),
                eq(campaignId)
        );
    }

    @Test
    void resume_acceptsCampaignWhenNoExecutableJobIsIneligible() throws Exception {
        AtomicReference<String> status = new AtomicReference<>("PAUSED");
        stubCampaign(status);
        when(db.queryForObject(contains("FROM campaign_jobs cj"), eq(Long.class),
                eq(campaignId), eq(workspaceId), eq(workspaceId)))
                .thenReturn(0L);
        when(db.update(contains("UPDATE campaigns"), eq(campaignId)))
                .thenAnswer(invocation -> {
                    status.set("RUNNING");
                    return 1;
                });

        CampaignDto.View actual = service.resume(campaignId);

        assertEquals("RUNNING", actual.status());
        verify(db).update(contains("UPDATE campaigns"), eq(campaignId));
    }

    private void stubCampaign(String status) throws Exception {
        stubCampaign(new AtomicReference<>(status));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubCampaign(AtomicReference<String> status) throws Exception {
        when(db.query(contains("FROM campaigns"), any(RowMapper.class), eq(campaignId)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject(eq("id"), eq(UUID.class))).thenReturn(campaignId);
                    when(rs.getObject(eq("workspace_id"), eq(UUID.class))).thenReturn(workspaceId);
                    when(rs.getString("name")).thenReturn("TEST_APPROVAL_CAMPAIGN");
                    when(rs.getString("status")).thenAnswer(i -> status.get());
                    when(rs.getObject(eq("created_at"), eq(OffsetDateTime.class)))
                            .thenReturn(OffsetDateTime.parse("2026-11-12T00:00:00Z"));
                    when(rs.getObject(eq("updated_at"), eq(OffsetDateTime.class)))
                            .thenReturn(OffsetDateTime.parse("2026-11-12T00:00:00Z"));
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubJob(String jobStatus, String postStatus, String approval,
                         String accountStatus, String platform) throws Exception {
        when(db.query(contains("FROM publishing_jobs j"), any(RowMapper.class),
                eq(jobId), eq(workspaceId), eq(workspaceId)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("job_status")).thenReturn(jobStatus);
                    when(rs.getString("post_status")).thenReturn(postStatus);
                    when(rs.getString("approval_status")).thenReturn(approval);
                    when(rs.getString("account_status")).thenReturn(accountStatus);
                    when(rs.getString("platform")).thenReturn(platform);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }
}
