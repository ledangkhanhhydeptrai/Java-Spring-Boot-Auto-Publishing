
package com.socialhub.publishingjob.bulk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FacebookBulkRunFinalizer {

    private final JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Value("${app.facebook.bulk-pilot-run-id:}")
    private String pilotRunId;

    @Scheduled(fixedDelayString =
            "${app.facebook.bulk-finalizer-delay-ms:10000}")
    public void finalizeCompletedRuns() {

        db.update("""
                UPDATE facebook_bulk_runs br
                SET status = 'COMPLETED',
                    finished_at = NOW()
                WHERE br.status = 'RUNNING'
                  AND (CAST(? AS uuid) IS NULL OR br.id=?)
                
                  AND EXISTS (
                      SELECT 1
                      FROM facebook_bulk_run_jobs bj
                      WHERE bj.bulk_run_id = br.id
                  )
                
                  AND NOT EXISTS (
                      SELECT 1
                      FROM facebook_bulk_run_jobs bj
                      JOIN publishing_jobs j
                        ON j.id = bj.publishing_job_id
                      WHERE bj.bulk_run_id = br.id
                        AND j.status IS DISTINCT FROM 'PUBLISHED'
                  )
                """, FacebookBulkPilotRules.runId(pilotRunId), FacebookBulkPilotRules.runId(pilotRunId));
    }
}
