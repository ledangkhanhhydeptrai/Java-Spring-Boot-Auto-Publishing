
package com.socialhub.publishingjob.bulk.service;

import com.socialhub.publishingjob.bulk.dto.CreateFacebookBulkRunRequest;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FacebookBulkRunService {

    private static final int MAX_BULK_SIZE = 100;

    private final JdbcTemplate db;
    private final WorkspaceBulkPermissionService permissions;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FacebookBulkRunService.class);

    @Transactional
    public FacebookBulkRunResponse create(
            UUID userId,
            CreateFacebookBulkRunRequest request
    ) {
        log.info("[BULK CREATE] ===== START ===== userId={}", userId);

        try {
            // 1. Validate request
            if (request == null
                    || request.workspaceId() == null
                    || request.jobIds() == null
                    || request.jobIds().isEmpty()) {

                log.warn("[BULK CREATE] Invalid request: missing workspaceId or jobIds");

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Workspace and job IDs are required"
                );
            }

            UUID workspaceId = request.workspaceId();
            List<UUID> ids = request.jobIds();

            log.info(
                    "[BULK CREATE] Request | workspaceId={} | jobIds={}",
                    workspaceId,
                    ids
            );

            // 2. Check workspace permission
            log.info("[BULK CREATE] Checking workspace permission...");

            permissions.requireManager(workspaceId, userId);

            log.info("[BULK CREATE] Workspace permission OK");

            // 3. Validate job IDs
            if (ids.size() > MAX_BULK_SIZE
                    || ids.contains(null)
                    || new HashSet<>(ids).size() != ids.size()) {

                log.warn(
                        "[BULK CREATE] Invalid job list | size={} | ids={}",
                        ids.size(),
                        ids
                );

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid bulk job list"
                );
            }

            List<UUID> sortedIds = ids.stream()
                    .sorted()
                    .toList();

            log.info("[BULK CREATE] Sorted jobs={}", sortedIds);

            // 4. Check eligibility for each job
            for (UUID jobId : sortedIds) {
                log.info("[BULK CREATE] Checking eligibility | jobId={}", jobId);

                List<UUID> eligible = db.query("""
                                SELECT j.id
                                FROM publishing_jobs j
                                JOIN posts p
                                    ON p.id = j.post_id
                                JOIN social_accounts a
                                    ON a.id = j.social_account_id
                                WHERE j.id = ?
                                  AND p.workspace_id = ?
                                  AND a.workspace_id = ?
                                  AND a.platform = 'FACEBOOK_PAGE'
                                  AND a.status = 'CONNECTED'
                                  AND p.status IN ('READY', 'SCHEDULED')
                                  AND p.approval_status = 'APPROVED'
                                  AND j.status = 'PENDING'
                                  AND j.attempt_count = 0
                                  AND j.external_post_id IS NULL
                                
                                  -- Reject media that has not finished uploading.
                                  -- Posts with uploaded Cloudinary media are allowed.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM post_media m
                                      WHERE m.post_id = p.id
                                        AND (
                                            m.public_url IS NULL
                                            OR BTRIM(m.public_url) = ''
                                        )
                                  )
                                
                                  -- Prevent putting the same job into multiple bulk runs.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM facebook_bulk_run_jobs bj
                                      WHERE bj.publishing_job_id = j.id
                                  )
                                
                                  -- Prevent conflicts with campaign jobs.
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM campaign_jobs cj
                                      WHERE cj.publishing_job_id = j.id
                                  )
                                
                                FOR UPDATE OF j
                                """,
                        (rs, rowNum) ->
                                rs.getObject("id", UUID.class),
                        jobId,
                        workspaceId,
                        workspaceId
                );

                log.info(
                        "[BULK CREATE] Eligibility result | jobId={} | eligibleCount={}",
                        jobId,
                        eligible.size()
                );

                if (eligible.size() != 1) {
                    log.warn(
                            "[BULK CREATE] Job not eligible | jobId={}",
                            jobId
                    );

                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Job is not eligible: " + jobId
                    );
                }
            }

            log.info("[BULK CREATE] All {} jobs are eligible", sortedIds.size());

            // 5. Generate Bulk Run ID
            UUID runId = UUID.randomUUID();

            log.info(
                    "[BULK CREATE] Inserting bulk run | runId={} | workspaceId={}",
                    runId,
                    workspaceId
            );

            // 6. Insert Bulk Run
            int insertedRun = db.update("""
                            INSERT INTO facebook_bulk_runs (
                                id,
                                workspace_id,
                                status
                            )
                            VALUES (?, ?, 'DRAFT')
                            """,
                    runId,
                    workspaceId
            );

            log.info(
                    "[BULK CREATE] Bulk Run INSERT result | runId={} | affectedRows={}",
                    runId,
                    insertedRun
            );

            if (insertedRun != 1) {
                throw new IllegalStateException(
                        "Expected 1 inserted bulk run, got " + insertedRun
                );
            }

            // 7. Link jobs to Bulk Run
            for (UUID jobId : sortedIds) {
                log.info(
                        "[BULK CREATE] Inserting bulk job link | runId={} | jobId={}",
                        runId,
                        jobId
                );

                int insertedLink = db.update("""
                                INSERT INTO facebook_bulk_run_jobs (
                                    bulk_run_id,
                                    publishing_job_id
                                )
                                VALUES (?, ?)
                                """,
                        runId,
                        jobId
                );

                log.info(
                        "[BULK CREATE] Bulk Job INSERT result | jobId={} | affectedRows={}",
                        jobId,
                        insertedLink
                );

                if (insertedLink != 1) {
                    throw new IllegalStateException(
                            "Expected 1 inserted bulk job link for " + jobId
                    );
                }
            }

            // 8. Build response
            FacebookBulkRunResponse response = new FacebookBulkRunResponse(
                    runId,
                    workspaceId,
                    "DRAFT",
                    sortedIds.size()
            );

            log.info(
                    "[BULK CREATE] SUCCESS | runId={} | totalJobs={} | status=DRAFT",
                    runId,
                    sortedIds.size()
            );

            return response;

        } catch (ResponseStatusException e) {
            log.warn(
                    "[BULK CREATE] Business validation failed | status={} | reason={}",
                    e.getStatusCode(),
                    e.getReason(),
                    e
            );
            throw e;

        } catch (org.springframework.dao.DataAccessException e) {
            log.error(
                    "[BULK CREATE] DATABASE ERROR | userId={} | rootCause={}",
                    userId,
                    e.getMostSpecificCause().getMessage(),
                    e
            );
            throw e;

        } catch (Exception e) {
            log.error(
                    "[BULK CREATE] UNEXPECTED ERROR | userId={}",
                    userId,
                    e
            );
            throw e;

        } finally {
            log.info("[BULK CREATE] ===== END =====");
        }
    }


    @Transactional
    public FacebookBulkRunResponse start(
            UUID userId,
            UUID runId
    ) {
        log.info(
                "[BULK START] ===== START ===== userId={} | runId={}",
                userId,
                runId
        );

        try {
            // 1. Kiểm tra Bulk Run
            if (runId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Bulk Run ID is required"
                );
            }

            log.info("[BULK START] Finding Bulk Run for update...");

            BulkRun run = findForUpdate(runId);

            log.info(
                    "[BULK START] Bulk Run found | workspaceId={} | status={}",
                    run.workspaceId(),
                    run.status()
            );

            // 2. Kiểm tra quyền quản lý Workspace
            permissions.requireManager(run.workspaceId(), userId);

            log.info("[BULK START] Workspace permission OK");

            // 3. Chỉ cho phép start DRAFT
            if (!"DRAFT".equals(run.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Only DRAFT bulk runs can be started"
                );
            }

            // 4. Đếm tổng số Jobs được liên kết
            Integer totalJobs = db.queryForObject("""
                            SELECT COUNT(*)
                            FROM facebook_bulk_run_jobs
                            WHERE bulk_run_id = ?
                            """,
                    Integer.class,
                    runId
            );

            log.info(
                    "[BULK START] Total linked Jobs={}",
                    totalJobs
            );

            if (totalJobs == null || totalJobs == 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Bulk Run has no Publishing Jobs"
                );
            }

            // 5. Kiểm tra lại điều kiện của các Job
            Integer invalidCount = db.queryForObject("""
                            SELECT COUNT(*)
                            FROM facebook_bulk_run_jobs bj
                            JOIN publishing_jobs j
                                ON j.id = bj.publishing_job_id
                            JOIN posts p
                                ON p.id = j.post_id
                            JOIN social_accounts a
                                ON a.id = j.social_account_id
                            WHERE bj.bulk_run_id = ?
                              AND (
                                   j.status <> 'PENDING'
                                   OR j.attempt_count <> 0
                                   OR j.external_post_id IS NOT NULL
                            
                                   -- Không cho phép Job thuộc Campaign.
                                   OR EXISTS (
                                       SELECT 1
                                       FROM campaign_jobs cj
                                       WHERE cj.publishing_job_id = j.id
                                   )
                            
                                   -- Workspace phải hợp lệ.
                                   OR p.workspace_id <> ?
                                   OR a.workspace_id <> ?
                            
                                   -- Chỉ Facebook Page đã kết nối.
                                   OR a.platform <> 'FACEBOOK_PAGE'
                                   OR a.status <> 'CONNECTED'
                            
                                   -- Bài viết phải sẵn sàng và được duyệt.
                                   OR p.status NOT IN ('READY', 'SCHEDULED')
                                   OR p.approval_status IS DISTINCT FROM 'APPROVED'
                            
                                   -- Chỉ chặn Media chưa upload thành công.
                                   -- Cho phép Post có ảnh Cloudinary hợp lệ.
                                   OR EXISTS (
                                       SELECT 1
                                       FROM post_media m
                                       WHERE m.post_id = p.id
                                         AND (
                                             m.public_url IS NULL
                                             OR BTRIM(m.public_url) = ''
                                         )
                                   )
                              )
                            """,
                    Integer.class,
                    runId,
                    run.workspaceId(),
                    run.workspaceId()
            );

            log.info(
                    "[BULK START] Validation | totalJobs={} | invalidJobs={}",
                    totalJobs,
                    invalidCount
            );

            if (invalidCount == null || invalidCount > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Some bulk jobs are no longer eligible"
                );
            }

            // 6. Chuyển Bulk Run sang RUNNING
            log.info(
                    "[BULK START] Updating Bulk Run to RUNNING | runId={}",
                    runId
            );

            int updated = db.update("""
                            UPDATE facebook_bulk_runs
                            SET status = 'RUNNING',
                                started_at = NOW()
                            WHERE id = ?
                              AND status = 'DRAFT'
                            """,
                    runId
            );

            log.info(
                    "[BULK START] Update result | affectedRows={}",
                    updated
            );

            if (updated != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Bulk run state changed"
                );
            }

            // 7. Trả kết quả
            FacebookBulkRunResponse response = getResponse(runId);

            log.info(
                    "[BULK START] SUCCESS | runId={} | status={} | totalJobs={}",
                    runId,
                    response.status(),
                    response.totalJobs()
            );

            return response;

        } catch (ResponseStatusException e) {
            log.warn(
                    "[BULK START] Business validation failed | status={} | reason={}",
                    e.getStatusCode(),
                    e.getReason(),
                    e
            );
            throw e;

        } catch (org.springframework.dao.DataAccessException e) {
            log.error(
                    "[BULK START] DATABASE ERROR | runId={} | rootCause={}",
                    runId,
                    e.getMostSpecificCause().getMessage(),
                    e
            );
            throw e;

        } catch (Exception e) {
            log.error(
                    "[BULK START] UNEXPECTED ERROR | runId={}",
                    runId,
                    e
            );
            throw e;

        } finally {
            log.info("[BULK START] ===== END =====");
        }
    }


    @Transactional
    public FacebookBulkRunResponse cancel(
            UUID userId,
            UUID runId
    ) {
        BulkRun run = findForUpdate(runId);

        permissions.requireManager(run.workspaceId(), userId);

        if (!"DRAFT".equals(run.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only DRAFT bulk runs can be cancelled safely"
            );
        }

        db.update("""
                        UPDATE facebook_bulk_runs
                        SET status = 'CANCELLED',
                            finished_at = NOW()
                        WHERE id = ?
                          AND status = 'DRAFT'
                        """,
                runId
        );

        return getResponse(runId);
    }

    @Transactional(readOnly = true)
    public FacebookBulkRunResponse get(
            UUID userId,
            UUID runId
    ) {
        BulkRun run = find(runId);

        permissions.requireManager(run.workspaceId(), userId);

        return getResponse(runId);
    }

    private record BulkRun(
            UUID workspaceId,
            String status
    ) {
    }

    private BulkRun find(UUID runId) {
        return db.query("""
                        SELECT workspace_id, status
                        FROM facebook_bulk_runs
                        WHERE id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Bulk run not found"
                        );
                    }

                    return new BulkRun(
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("status")
                    );
                },
                runId
        );
    }

    private BulkRun findForUpdate(UUID runId) {
        return db.query("""
                        SELECT workspace_id, status
                        FROM facebook_bulk_runs
                        WHERE id = ?
                        FOR UPDATE
                        """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Bulk run not found"
                        );
                    }

                    return new BulkRun(
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("status")
                    );
                },
                runId
        );
    }

    private FacebookBulkRunResponse getResponse(UUID runId) {
        return db.queryForObject("""
                        SELECT
                            r.id,
                            r.workspace_id,
                            r.status,
                            COUNT(bj.id) AS total_jobs
                        FROM facebook_bulk_runs r
                        LEFT JOIN facebook_bulk_run_jobs bj
                            ON bj.bulk_run_id = r.id
                        WHERE r.id = ?
                        GROUP BY r.id, r.workspace_id, r.status
                        """,
                (rs, rowNum) -> new FacebookBulkRunResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("total_jobs")
                ),
                runId
        );
    }
}
