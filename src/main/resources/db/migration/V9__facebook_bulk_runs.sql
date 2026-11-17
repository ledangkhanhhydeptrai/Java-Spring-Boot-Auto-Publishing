
CREATE TABLE facebook_bulk_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL
        REFERENCES workspaces(id),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT chk_facebook_bulk_run_status
        CHECK (status IN (
            'DRAFT',
            'RUNNING',
            'COMPLETED',
            'CANCELLED'
        ))
);

CREATE TABLE facebook_bulk_run_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bulk_run_id UUID NOT NULL
        REFERENCES facebook_bulk_runs(id),
    publishing_job_id UUID NOT NULL
        REFERENCES publishing_jobs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_facebook_bulk_job
        UNIQUE (publishing_job_id),
    CONSTRAINT uq_facebook_bulk_run_job
        UNIQUE (bulk_run_id, publishing_job_id)
);

CREATE INDEX idx_facebook_bulk_runs_workspace
    ON facebook_bulk_runs(workspace_id, status);

CREATE INDEX idx_facebook_bulk_run_jobs_run
    ON facebook_bulk_run_jobs(bulk_run_id);
