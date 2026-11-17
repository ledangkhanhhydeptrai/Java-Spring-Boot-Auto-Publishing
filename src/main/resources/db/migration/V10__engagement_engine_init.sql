
-- SocialHub Engagement Engine V1
-- PostgreSQL 17
-- Create-only migration. Does not modify publishing tables.

CREATE TABLE engagement_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,

    CONSTRAINT chk_engagement_campaign_status
        CHECK (status IN (
            'DRAFT', 'RUNNING', 'PAUSED',
            'COMPLETED', 'CANCELLED'
        )),

    CONSTRAINT uq_engagement_campaign_workspace
        UNIQUE (id, workspace_id)
);

CREATE TABLE engagement_targets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    platform VARCHAR(30) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    external_target_id VARCHAR(255) NOT NULL,
    target_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_engagement_target_platform
        CHECK (platform IN ('FACEBOOK_PAGE', 'THREADS')),

    CONSTRAINT chk_engagement_target_type
        CHECK (target_type IN ('POST', 'COMMENT', 'REPLY')),

    CONSTRAINT uq_engagement_target
        UNIQUE (
            workspace_id,
            platform,
            target_type,
            external_target_id
        ),

    CONSTRAINT uq_engagement_target_workspace
        UNIQUE (id, workspace_id)
);

CREATE TABLE engagement_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    workspace_id UUID NOT NULL REFERENCES workspaces(id),

    campaign_id UUID,
    target_id UUID NOT NULL,
    social_account_id UUID NOT NULL REFERENCES social_accounts(id),

    action_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',

    content TEXT,
    scheduled_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,

    attempt_count INTEGER NOT NULL DEFAULT 0,
    external_result_id VARCHAR(255),
    last_error TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT fk_engagement_task_campaign
        FOREIGN KEY (campaign_id, workspace_id)
        REFERENCES engagement_campaigns(id, workspace_id),

    CONSTRAINT fk_engagement_task_target
        FOREIGN KEY (target_id, workspace_id)
        REFERENCES engagement_targets(id, workspace_id),

    CONSTRAINT chk_engagement_action
        CHECK (action_type IN (
            'LIKE', 'COMMENT', 'REPLY',
            'SHARE', 'REPOST', 'QUOTE'
        )),

    CONSTRAINT chk_engagement_task_status
        CHECK (status IN (
            'PENDING', 'PROCESSING', 'SUCCEEDED',
            'FAILED', 'NEEDS_REVIEW', 'CANCELLED'
        )),

    CONSTRAINT chk_engagement_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE TABLE engagement_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    engagement_task_id UUID NOT NULL
        REFERENCES engagement_tasks(id),

    attempt_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',

    error_code VARCHAR(100),
    error_message TEXT,
    external_result_id VARCHAR(255),

    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,

    CONSTRAINT uq_engagement_attempt
        UNIQUE (engagement_task_id, attempt_number),

    CONSTRAINT chk_engagement_attempt_number
        CHECK (attempt_number > 0),

    CONSTRAINT chk_engagement_attempt_status
        CHECK (status IN (
            'PROCESSING', 'SUCCESS',
            'FAILED', 'UNKNOWN'
        ))
);

CREATE TABLE engagement_account_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    social_account_id UUID NOT NULL
        REFERENCES social_accounts(id),

    action_type VARCHAR(30) NOT NULL,
    next_allowed_at TIMESTAMPTZ,
    cooldown_until TIMESTAMPTZ,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_engagement_account_action
        UNIQUE (social_account_id, action_type),

    CONSTRAINT chk_engagement_limit_action
        CHECK (action_type IN (
            'LIKE', 'COMMENT', 'REPLY',
            'SHARE', 'REPOST', 'QUOTE'
        ))
);

CREATE TABLE engagement_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    engagement_task_id UUID NOT NULL
        REFERENCES engagement_tasks(id),

    event_type VARCHAR(80) NOT NULL,
    event_message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_engagement_campaigns_workspace
    ON engagement_campaigns(workspace_id, status);

CREATE INDEX idx_engagement_tasks_queue
    ON engagement_tasks(status, scheduled_at, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_engagement_tasks_workspace
    ON engagement_tasks(workspace_id, created_at DESC);

CREATE INDEX idx_engagement_tasks_account
    ON engagement_tasks(social_account_id, status);

CREATE INDEX idx_engagement_attempts_task
    ON engagement_attempts(engagement_task_id);

CREATE INDEX idx_engagement_events_task
    ON engagement_events(engagement_task_id, created_at DESC);
