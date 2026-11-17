-- Keep legacy V16 intact; distinguish a routed LIVE command from observed events.
ALTER TABLE automation_job_outbox DROP CONSTRAINT IF EXISTS automation_job_outbox_state_check;
ALTER TABLE automation_job_outbox ADD CONSTRAINT automation_job_outbox_state_check
  CHECK (state IN ('PENDING','SENDING','SENT','OBSERVED','EXECUTED','REVIEW'));
