CREATE TABLE campaigns
(
    id           UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PAUSED',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_campaign_status
        CHECK (status IN ('RUNNING', 'PAUSED'))
);

CREATE TABLE campaign_jobs
(
    campaign_id       UUID NOT NULL
        REFERENCES campaigns (id) ON DELETE CASCADE,
    publishing_job_id UUID NOT NULL UNIQUE
        REFERENCES publishing_jobs (id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, publishing_job_id)
);

CREATE INDEX idx_campaigns_workspace
    ON campaigns (workspace_id, created_at DESC);

CREATE INDEX idx_campaign_jobs_campaign
    ON campaign_jobs (campaign_id);
