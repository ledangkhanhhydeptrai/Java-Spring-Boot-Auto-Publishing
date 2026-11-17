-- Requires the pre-existing facebook_page_connections table (V18).
-- No existing jobs are changed: NULL retains their previous publisher behavior.
ALTER TABLE publishing_jobs
    ADD COLUMN facebook_connection_id UUID NULL;

ALTER TABLE publishing_jobs
    ADD CONSTRAINT fk_publishing_jobs_facebook_connection
    FOREIGN KEY (facebook_connection_id)
    REFERENCES facebook_page_connections(id) ON DELETE RESTRICT;

CREATE INDEX ix_publishing_jobs_facebook_connection_active
    ON publishing_jobs (facebook_connection_id, status)
    WHERE facebook_connection_id IS NOT NULL;
