
CREATE TABLE post_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    post_id UUID NOT NULL,
    reviewed_by UUID NOT NULL,

    decision VARCHAR(30) NOT NULL,
    note TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_post_approvals_post
        FOREIGN KEY (post_id)
        REFERENCES posts(id),

    CONSTRAINT fk_post_approvals_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES users(id),

    CONSTRAINT chk_post_approvals_decision
        CHECK (decision IN ('APPROVED', 'REJECTED'))
);

CREATE INDEX idx_post_approvals_post_id
    ON post_approvals(post_id);

CREATE INDEX idx_post_approvals_reviewed_by
    ON post_approvals(reviewed_by);
