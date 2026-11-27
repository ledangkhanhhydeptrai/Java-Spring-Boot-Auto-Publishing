-- Additive only. Apply after V39; no reset or historical Job changes.
ALTER TABLE facebook_authorized_identities
  ADD COLUMN health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN last_checked_at TIMESTAMPTZ,
  ADD COLUMN health_message TEXT;
CREATE INDEX ix_facebook_identity_health_due
  ON facebook_authorized_identities (last_checked_at NULLS FIRST, workspace_id, facebook_user_id)
  WHERE user_token_encrypted IS NOT NULL AND health_status <> 'DISCONNECTED';
