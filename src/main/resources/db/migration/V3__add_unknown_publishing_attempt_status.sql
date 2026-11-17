-- V3: Add UNKNOWN status to publishing_attempts.
-- Reconcile databases where the constraint
-- may already have been changed manually.

DO
$$
DECLARE
constraint_record RECORD;
BEGIN
FOR constraint_record IN
SELECT con.conname
FROM pg_constraint con
WHERE con.conrelid = 'public.publishing_attempts'::regclass
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%status%'
    LOOP
        EXECUTE format(
            'ALTER TABLE public.publishing_attempts DROP CONSTRAINT %I',
            constraint_record.conname
        );
END LOOP;

ALTER TABLE public.publishing_attempts
    ADD CONSTRAINT chk_attempt_status
        CHECK (
            status IN (
                       'PROCESSING',
                       'SUCCESS',
                       'FAILED',
                       'UNKNOWN'
                )
            );
END $$;
