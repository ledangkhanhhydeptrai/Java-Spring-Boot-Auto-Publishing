package com.socialhub.campaign.service.implement;

import com.socialhub.campaign.dto.CampaignDto;
import com.socialhub.campaign.service.interfaces.CampaignService;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private final JdbcTemplate db;
    private final AccessService access;

    private record JobEligibility(
            String jobStatus,
            String postStatus,
            String approvalStatus,
            String accountStatus,
            String platform
    ) {
    }

    private CampaignDto.View mapView(
            ResultSet rs,
            int rowNum
    ) throws SQLException {

        return new CampaignDto.View(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private CampaignDto.View findCampaign(UUID campaignId) {

        List<CampaignDto.View> result = db.query("""
                        SELECT
                            id,
                            workspace_id,
                            name,
                            status,
                            created_at,
                            updated_at
                        FROM campaigns
                        WHERE id = ?
                        """,
                this::mapView,
                campaignId
        );

        if (result.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Campaign not found"
            );
        }

        return result.getFirst();
    }

    private CampaignDto.View lockCampaign(UUID campaignId) {

        List<CampaignDto.View> result = db.query("""
                        SELECT
                            id,
                            workspace_id,
                            name,
                            status,
                            created_at,
                            updated_at
                        FROM campaigns
                        WHERE id = ?
                        FOR UPDATE
                        """,
                this::mapView,
                campaignId
        );

        if (result.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Campaign not found"
            );
        }

        return result.getFirst();
    }

    @Override
    @Transactional
    public CampaignDto.View create(CampaignDto.Create request) {

        access.admin(request.workspaceId());

        if (request.name() == null
                || request.name().isBlank()
                || request.name().length() > 255) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid campaign name"
            );
        }

        UUID id = UUID.randomUUID();

        db.update("""
                        INSERT INTO campaigns (
                            id,
                            workspace_id,
                            name,
                            status
                        )
                        VALUES (?, ?, ?, 'PAUSED')
                        """,
                id,
                request.workspaceId(),
                request.name().trim()
        );

        return findCampaign(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CampaignDto.View> list(UUID workspaceId) {

        access.workspace(workspaceId);

        return db.query("""
                        SELECT
                            id,
                            workspace_id,
                            name,
                            status,
                            created_at,
                            updated_at
                        FROM campaigns
                        WHERE workspace_id = ?
                        ORDER BY created_at DESC, id DESC
                        """,
                this::mapView,
                workspaceId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignDto.View detail(UUID campaignId) {

        CampaignDto.View campaign = findCampaign(campaignId);

        access.workspace(campaign.workspaceId());

        return campaign;
    }

    @Override
    @Transactional
    public CampaignDto.View pause(UUID campaignId) {

        CampaignDto.View campaign = lockCampaign(campaignId);

        access.admin(campaign.workspaceId());

        if ("PAUSED".equals(campaign.status())) {
            return campaign;
        }

        db.update("""
                        UPDATE campaigns
                        SET status = 'PAUSED',
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                campaignId
        );

        return findCampaign(campaignId);
    }

    @Override
    @Transactional
    public CampaignDto.View resume(UUID campaignId) {

        CampaignDto.View campaign = lockCampaign(campaignId);

        access.admin(campaign.workspaceId());

        if ("RUNNING".equals(campaign.status())) {
            return campaign;
        }

        // Revalidate executable jobs before activating the campaign.
        // Published/finished jobs are historical and do not require fresh approval.
        Long ineligibleJobs = db.queryForObject("""
                        SELECT COUNT(*)
                        FROM campaign_jobs cj
                        JOIN publishing_jobs j ON j.id = cj.publishing_job_id
                        JOIN posts p ON p.id = j.post_id
                        JOIN social_accounts a ON a.id = j.social_account_id
                        WHERE cj.campaign_id = ?
                          AND j.status IN ('PENDING', 'FAILED')
                          AND (
                              p.approval_status IS DISTINCT FROM 'APPROVED'
                              OR p.status NOT IN ('READY', 'SCHEDULED')
                              OR p.workspace_id <> ?
                              OR a.workspace_id <> ?
                              OR a.status <> 'CONNECTED'
                              OR a.platform NOT IN ('FACEBOOK_PAGE', 'THREADS')
                          )
                        """, Long.class,
                campaignId,
                campaign.workspaceId(),
                campaign.workspaceId()
        );

        if (ineligibleJobs == null || ineligibleJobs > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Campaign contains jobs without approval or publishing eligibility"
            );
        }

        db.update("""
                        UPDATE campaigns
                        SET status = 'RUNNING',
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                campaignId
        );

        return findCampaign(campaignId);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignDto.Progress progress(UUID campaignId) {

        CampaignDto.View campaign = findCampaign(campaignId);

        access.workspace(campaign.workspaceId());

        return db.queryForObject("""
                        SELECT
                            COUNT(j.id) AS total,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'PENDING'
                            ) AS pending,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'PROCESSING'
                            ) AS processing,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'PUBLISHED'
                            ) AS published,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'FAILED'
                            ) AS failed,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'NEEDS_REVIEW'
                            ) AS needs_review,
                            COUNT(j.id) FILTER (
                                WHERE j.status = 'CANCELLED'
                            ) AS cancelled
                        FROM campaign_jobs cj
                        JOIN publishing_jobs j
                            ON j.id = cj.publishing_job_id
                        WHERE cj.campaign_id = ?
                        """,
                (rs, rowNum) -> {

                    long total = rs.getLong("total");
                    long published = rs.getLong("published");
                    long cancelled = rs.getLong("cancelled");

                    // Tỷ lệ hoàn tất: PUBLISHED + CANCELLED.
                    // NEEDS_REVIEW chưa được tính là hoàn tất.
                    double percent = total == 0
                            ? 0.0
                            : (published + cancelled) * 100.0 / total;

                    return new CampaignDto.Progress(
                            campaign.id(),
                            campaign.name(),
                            campaign.status(),
                            total,
                            rs.getLong("pending"),
                            rs.getLong("processing"),
                            published,
                            rs.getLong("failed"),
                            rs.getLong("needs_review"),
                            cancelled,
                            Math.round(percent * 100.0) / 100.0
                    );
                },
                campaignId
        );
    }

    @Override
    @Transactional
    public CampaignDto.Progress addJob(
            UUID campaignId,
            CampaignDto.AddJob request
    ) {

        CampaignDto.View campaign = lockCampaign(campaignId);

        access.admin(campaign.workspaceId());

        if (!"PAUSED".equals(campaign.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Pause campaign before adding jobs"
            );
        }

        if (request.publishingJobId() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Publishing Job ID is required"
            );
        }

        List<JobEligibility> jobs = db.query("""
                        SELECT j.status AS job_status,
                               p.status AS post_status,
                               p.approval_status,
                               a.status AS account_status,
                               a.platform AS platform
                        FROM publishing_jobs j
                        JOIN posts p ON p.id = j.post_id
                        JOIN social_accounts a
                            ON a.id = j.social_account_id
                        WHERE j.id = ?
                          AND p.workspace_id = ?
                          AND a.workspace_id = ?
                        FOR UPDATE OF j
                        """,
                (rs, rowNum) -> new JobEligibility(
                        rs.getString("job_status"),
                        rs.getString("post_status"),
                        rs.getString("approval_status"),
                        rs.getString("account_status"),
                        rs.getString("platform")
                ),
                request.publishingJobId(),
                campaign.workspaceId(),
                campaign.workspaceId()
        );

        if (jobs.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Publishing Job not found in campaign workspace"
            );
        }

        JobEligibility job = jobs.getFirst();

        if (!"PENDING".equals(job.jobStatus())
                && !"FAILED".equals(job.jobStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only PENDING or FAILED jobs can be added"
            );
        }

        if (!"APPROVED".equals(job.approvalStatus())
                || !("READY".equals(job.postStatus()) || "SCHEDULED".equals(job.postStatus()))
                || !"CONNECTED".equals(job.accountStatus())
                || !("FACEBOOK_PAGE".equals(job.platform()) || "THREADS".equals(job.platform()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Post approval or publishing eligibility is missing"
            );
        }

        int inserted = db.update("""
                        INSERT INTO campaign_jobs (
                            campaign_id,
                            publishing_job_id
                        )
                        VALUES (?, ?)
                        ON CONFLICT (publishing_job_id) DO NOTHING
                        """,
                campaignId,
                request.publishingJobId()
        );

        if (inserted != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Publishing Job already belongs to a campaign"
            );
        }

        return progress(campaignId);
    }
}
