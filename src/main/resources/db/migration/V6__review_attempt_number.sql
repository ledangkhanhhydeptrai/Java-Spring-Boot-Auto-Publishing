-- V6: Associate manual reviews with publishing attempts.
-- Preserve existing review data.

ALTER TABLE publishing_reviews
    ADD COLUMN IF NOT EXISTS attempt_number INTEGER;

UPDATE publishing_reviews r
SET attempt_number = j.attempt_count
    FROM publishing_jobs j
WHERE r.publishing_job_id = j.id
  AND r.attempt_number IS NULL;

ALTER TABLE publishing_reviews
    ALTER COLUMN attempt_number SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_review_attempt_positive'
          AND conrelid = 'public.publishing_reviews'::regclass
    ) THEN
ALTER TABLE publishing_reviews
    ADD CONSTRAINT chk_review_attempt_positive
        CHECK (attempt_number > 0);
END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_publishing_reviews_job_attempt
    ON publishing_reviews (publishing_job_id, attempt_number);