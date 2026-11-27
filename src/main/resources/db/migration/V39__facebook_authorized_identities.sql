-- New additive migration. Existing Page grants and scheduled jobs stay unchanged.
CREATE TABLE facebook_authorized_identities (
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    facebook_user_id VARCHAR(128) NOT NULL,
    display_name TEXT,
    user_token_encrypted TEXT,
    token_expires_at TIMESTAMPTZ,
    last_authorized_at TIMESTAMPTZ NOT NULL,
    last_synced_at TIMESTAMPTZ,
    PRIMARY KEY (workspace_id, facebook_user_id)
);
INSERT INTO facebook_authorized_identities (workspace_id, facebook_user_id, last_authorized_at)
SELECT a.workspace_id, c.facebook_user_id, MAX(c.last_connected_at)
FROM facebook_page_connections c JOIN social_accounts a ON a.id = c.social_account_id
WHERE a.platform = 'FACEBOOK_PAGE'
GROUP BY a.workspace_id, c.facebook_user_id
ON CONFLICT DO NOTHING;
