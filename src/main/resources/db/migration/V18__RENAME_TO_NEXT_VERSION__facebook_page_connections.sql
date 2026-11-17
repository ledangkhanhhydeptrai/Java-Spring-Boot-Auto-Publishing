-- Rename to the NEXT FREE Flyway version before placing under src/main/resources/db/migration.
-- Do not modify or reuse an applied Flyway V*.sql migration.
CREATE TABLE facebook_page_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    social_account_id UUID NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
    facebook_user_id VARCHAR(128) NOT NULL,
    access_token_encrypted TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    last_connected_at TIMESTAMPTZ NOT NULL,
    token_expires_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_facebook_page_connection_user UNIQUE (social_account_id, facebook_user_id),
    CONSTRAINT chk_facebook_page_connection_token CHECK (length(btrim(access_token_encrypted)) > 0)
);
CREATE INDEX ix_facebook_page_connections_social_account
    ON facebook_page_connections(social_account_id);
