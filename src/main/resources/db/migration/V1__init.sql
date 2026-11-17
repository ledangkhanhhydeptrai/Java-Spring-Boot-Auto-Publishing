CREATE TABLE users
(
    id            UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash TEXT         NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE TABLE workspaces
(
    id         UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE TABLE workspace_members
(
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT chk_member_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);
CREATE TABLE social_accounts
(
    id                     UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    workspace_id           UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    platform               VARCHAR(30)  NOT NULL,
    external_account_id    VARCHAR(255) NOT NULL,
    display_name           VARCHAR(255) NOT NULL,
    status                 VARCHAR(30)  NOT NULL DEFAULT 'DISCONNECTED',
    access_token_encrypted TEXT,
    token_expires_at       TIMESTAMPTZ,
    last_connected_at      TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, platform, external_account_id),
    CONSTRAINT chk_platform CHECK (platform IN ('FACEBOOK_PAGE', 'THREADS')),
    CONSTRAINT chk_account_status CHECK (status IN ('CONNECTED', 'DISCONNECTED', 'EXPIRED'))
);
CREATE TABLE posts
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    topic        VARCHAR(255),
    content      TEXT        NOT NULL,
    status       VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    scheduled_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_post_status CHECK (status IN ('DRAFT', 'READY', 'SCHEDULED', 'ARCHIVED'))
);
CREATE TABLE post_media
(
    id            UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    post_id       UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    source_url    TEXT        NOT NULL,
    public_url    TEXT,
    media_type    VARCHAR(30) NOT NULL DEFAULT 'IMAGE',
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT chk_media_order CHECK (display_order >= 0)
);
CREATE TABLE publishing_jobs
(
    id                    UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    post_id               UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    social_account_id     UUID        NOT NULL REFERENCES social_accounts (id) ON DELETE CASCADE,
    status                VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count         INTEGER     NOT NULL DEFAULT 0,
    scheduled_at          TIMESTAMPTZ,
    published_at          TIMESTAMPTZ,
    external_post_id      VARCHAR(255),
    published_url         TEXT,
    last_error            TEXT,
    next_retry_at         TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (post_id, social_account_id),
    CONSTRAINT chk_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'CANCELLED'))
);
CREATE TABLE publishing_attempts
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    publishing_job_id UUID        NOT NULL REFERENCES publishing_jobs (id) ON DELETE CASCADE,
    attempt_number    INTEGER     NOT NULL,
    status            VARCHAR(30) NOT NULL,
    error_message     TEXT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMPTZ,
    UNIQUE (publishing_job_id, attempt_number),
    CONSTRAINT chk_attempt_status CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED'))
);
CREATE TABLE facebook_groups
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    workspace_id      UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    external_group_id VARCHAR(255) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    group_url         TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, external_group_id)
);
CREATE TABLE group_publishing_tasks
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    post_id           UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    facebook_group_id UUID        NOT NULL REFERENCES facebook_groups (id) ON DELETE CASCADE,
    status            VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    scheduled_at      TIMESTAMPTZ,
    published_at      TIMESTAMPTZ,
    published_url     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (post_id, facebook_group_id),
    CONSTRAINT chk_group_status CHECK (status IN
                                       ('PENDING', 'READY', 'PROCESSING', 'NEEDS_REVIEW', 'FAILED', 'PUBLISHED',
                                        'CANCELLED'))
);
CREATE INDEX idx_members_user ON workspace_members (user_id);
CREATE INDEX idx_posts_workspace ON posts (workspace_id);
CREATE INDEX idx_media_post ON post_media (post_id);
CREATE INDEX idx_jobs_status_schedule ON publishing_jobs (status, scheduled_at);
CREATE INDEX idx_attempts_job ON publishing_attempts (publishing_job_id);
CREATE INDEX idx_groups_workspace ON facebook_groups (workspace_id);
CREATE INDEX idx_group_tasks_schedule ON group_publishing_tasks (status, scheduled_at);
