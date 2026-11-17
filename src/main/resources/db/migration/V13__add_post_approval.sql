ALTER TABLE posts
    ADD COLUMN approval_status VARCHAR(30)
        NOT NULL DEFAULT 'NOT_SUBMITTED',
    ADD COLUMN approved_by UUID,
    ADD COLUMN approved_at TIMESTAMPTZ;

ALTER TABLE posts
    ADD CONSTRAINT fk_posts_approved_by
    FOREIGN KEY (approved_by)
    REFERENCES users(id);