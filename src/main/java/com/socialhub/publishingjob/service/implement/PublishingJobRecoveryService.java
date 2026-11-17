
package com.socialhub.publishingjob.service.implement;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublishingJobRecoveryService {

    private static final int RECOVERY_BATCH_SIZE = 100;

    private static final String RECOVERY_ERROR =
            "Worker processing timeout. Publication outcome unknown. Manual verification required.";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Recover expired jobs for one publishing platform only.
     */
    @Transactional
    public int recoverExpiredJobsForPlatform(String platform) {

        if (!"THREADS".equals(platform)
                && !"FACEBOOK_PAGE".equals(platform)) {
            throw new IllegalArgumentException(
                    "Unsupported publishing platform: " + platform
            );
        }

        List<UUID> expiredJobIds = jdbcTemplate.query(
                """
                        SELECT j.id
                        FROM publishing_jobs j
                        JOIN social_accounts a
                            ON a.id = j.social_account_id
                        WHERE a.platform = ?
                          AND j.status = 'PROCESSING'
                          AND j.locked_until IS NOT NULL
                          AND j.locked_until < NOW()
                        ORDER BY j.locked_until, j.id
                        LIMIT ?
                        FOR UPDATE OF j SKIP LOCKED
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                platform,
                RECOVERY_BATCH_SIZE
        );

        int recovered = 0;

        for (UUID jobId : expiredJobIds) {

            int jobUpdated = jdbcTemplate.update(
                    """
                            UPDATE publishing_jobs
                            SET status = 'NEEDS_REVIEW',
                                last_error = ?,
                                next_retry_at = NULL,
                                locked_until = NULL
                            WHERE id = ?
                              AND status = 'PROCESSING'
                              AND locked_until < NOW()
                            """,
                    RECOVERY_ERROR,
                    jobId
            );

            if (jobUpdated != 1) {
                throw new IllegalStateException(
                        "Failed to recover publishing job: " + jobId
                );
            }

            int attemptUpdated = jdbcTemplate.update(
                    """
                            UPDATE publishing_attempts
                            SET status = 'UNKNOWN',
                                error_message = ?,
                                finished_at = NOW()
                            WHERE publishing_job_id = ?
                              AND status = 'PROCESSING'
                            """,
                    RECOVERY_ERROR,
                    jobId
            );

            if (attemptUpdated != 1) {
                throw new IllegalStateException(
                        "Expected exactly one PROCESSING attempt for job: "
                                + jobId
                                + ", updated: "
                                + attemptUpdated
                );
            }

            recovered++;
        }

        return recovered;
    }

    /**
     * Compatibility method for existing callers.
     * Scheduler should call recoverExpiredJobsForPlatform().
     */
    @Transactional
    public int recoverExpiredJobs() {
        return recoverExpiredJobsForPlatform("FACEBOOK_PAGE")
                + recoverExpiredJobsForPlatform("THREADS");
    }

    /**
     * Recover expired Facebook LIVE Bulk jobs.
     * <p>
     * Never retry automatically because Meta may have
     * accepted the publication before the worker stopped.
     */
    @Transactional
    public int recoverExpiredFacebookBulkJobs() {

        List<UUID> expiredJobIds = jdbcTemplate.query("""
                        SELECT j.id
                        FROM publishing_jobs j
                        
                        JOIN facebook_bulk_run_jobs bj
                            ON bj.publishing_job_id = j.id
                        
                        JOIN facebook_bulk_runs br
                            ON br.id = bj.bulk_run_id
                        
                        JOIN social_accounts a
                            ON a.id = j.social_account_id
                        
                        WHERE a.platform = 'FACEBOOK_PAGE'
                          AND br.status = 'RUNNING'
                          AND j.status = 'PROCESSING'
                          AND j.locked_until IS NOT NULL
                          AND j.locked_until < NOW()
                        
                        ORDER BY j.locked_until, j.id
                        
                        LIMIT ?
                        
                        FOR UPDATE OF j SKIP LOCKED
                        """,
                (rs, rowNum) ->
                        rs.getObject("id", UUID.class),
                RECOVERY_BATCH_SIZE
        );

        int recovered = 0;

        for (UUID jobId : expiredJobIds) {

            int jobUpdated = jdbcTemplate.update("""
                            UPDATE publishing_jobs
                            SET status = 'NEEDS_REVIEW',
                                last_error = ?,
                                next_retry_at = NULL,
                                locked_until = NULL
                            WHERE id = ?
                              AND status = 'PROCESSING'
                              AND locked_until < NOW()
                            """,
                    RECOVERY_ERROR,
                    jobId
            );

            if (jobUpdated != 1) {
                throw new IllegalStateException(
                        "Failed to recover Facebook Bulk job: "
                                + jobId
                );
            }

            int attemptUpdated = jdbcTemplate.update("""
                            UPDATE publishing_attempts
                            SET status = 'UNKNOWN',
                                error_message = ?,
                                finished_at = NOW()
                            WHERE publishing_job_id = ?
                              AND status = 'PROCESSING'
                            """,
                    RECOVERY_ERROR,
                    jobId
            );

            if (attemptUpdated != 1) {
                throw new IllegalStateException(
                        "Expected exactly one PROCESSING attempt for Bulk job: "
                                + jobId
                                + ", updated: "
                                + attemptUpdated
                );
            }

            recovered++;
        }

        return recovered;
    }
}




