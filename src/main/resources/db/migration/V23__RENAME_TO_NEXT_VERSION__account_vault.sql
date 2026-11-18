-- SocialHub Account Vault / Phase 1
-- IMPORTANT: Rename to the NEXT AVAILABLE Flyway version, e.g. V<next>__account_vault.sql,
-- after inspecting flyway_schema_history. Do not use an already-applied version.
-- Existing social_accounts remains the source of truth for OAuth-connected accounts.

CREATE TABLE account_vault_groups (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_account_vault_groups_name CHECK (LENGTH(TRIM(name)) > 0)
);

CREATE UNIQUE INDEX uq_account_vault_groups_workspace_name_ci
    ON account_vault_groups (workspace_id, LOWER(name));

CREATE INDEX ix_account_vault_groups_workspace
    ON account_vault_groups (workspace_id);

CREATE TABLE account_vault_group_members (
    group_id UUID NOT NULL REFERENCES account_vault_groups(id) ON DELETE CASCADE,
    social_account_id UUID NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, social_account_id)
);

CREATE INDEX ix_account_vault_members_account
    ON account_vault_group_members (social_account_id);

-- Speeds up paging/filtering for large inventories without touching tokens.
CREATE INDEX ix_social_accounts_vault_workspace_filter
    ON social_accounts (workspace_id, platform, status, id);

-- Defense in depth: reject cross-workspace memberships even if a future caller bypasses Java.
CREATE OR REPLACE FUNCTION account_vault_assert_same_workspace()
RETURNS TRIGGER AS $$
DECLARE
    group_workspace UUID;
    account_workspace UUID;
BEGIN
    SELECT workspace_id INTO group_workspace FROM account_vault_groups WHERE id = NEW.group_id;
    SELECT workspace_id INTO account_workspace FROM social_accounts WHERE id = NEW.social_account_id;
    IF group_workspace IS NULL OR account_workspace IS NULL OR group_workspace <> account_workspace THEN
        RAISE EXCEPTION 'Vault group and social account must belong to the same workspace';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_account_vault_members_same_workspace
BEFORE INSERT OR UPDATE ON account_vault_group_members
FOR EACH ROW EXECUTE FUNCTION account_vault_assert_same_workspace();

-- Note: workspace_id is checked by AccessService in API. Add a FOREIGN KEY to the
-- real Workspace table once its exact physical table name has been confirmed.
