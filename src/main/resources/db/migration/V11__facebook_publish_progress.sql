
-- ============================================
-- SOCIALHUB - FACEBOOK PUBLISHING PROGRESS
-- PostgreSQL 17
-- ============================================

-- 1. Lưu tiến độ upload từng ảnh lên Meta
CREATE TABLE IF NOT EXISTS facebook_photo_upload_progress (
    publishing_job_id UUID NOT NULL
        REFERENCES publishing_jobs(id),

    attempt_number INTEGER NOT NULL,

    image_position INTEGER NOT NULL
        CHECK (image_position > 0),

    image_url TEXT NOT NULL,

    status VARCHAR(32) NOT NULL
        CHECK (
            status IN (
                'REQUEST_STARTED',
                'UPLOADED'
            )
        ),

    meta_photo_id TEXT,

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT NOW(),

    PRIMARY KEY (
        publishing_job_id,
        attempt_number,
        image_position
    )
);

-- 2. Lưu tiến độ tạo bài viết Facebook
CREATE TABLE IF NOT EXISTS facebook_feed_publish_progress (
    publishing_job_id UUID NOT NULL
        REFERENCES publishing_jobs(id),

    attempt_number INTEGER NOT NULL,

    status VARCHAR(32) NOT NULL
        CHECK (
            status IN (
                'REQUEST_STARTED',
                'CONFIRMED'
            )
        ),

    meta_post_id TEXT,

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT NOW(),

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT NOW(),

    PRIMARY KEY (
        publishing_job_id,
        attempt_number
    )
);
