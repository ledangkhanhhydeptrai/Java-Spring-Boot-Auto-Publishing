ALTER TABLE public.publishing_jobs
DROP CONSTRAINT chk_job_status;

ALTER TABLE public.publishing_jobs
    ADD CONSTRAINT chk_job_status
        CHECK (
            status IN (
                       'PENDING',
                       'PROCESSING',
                       'PUBLISHED',
                       'FAILED',
                       'CANCELLED',
                       'NEEDS_REVIEW'
                )
            );