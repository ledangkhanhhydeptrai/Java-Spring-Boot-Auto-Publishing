ALTER TABLE public.post_media
DROP CONSTRAINT IF EXISTS chk_media_type;

ALTER TABLE public.post_media
ADD CONSTRAINT chk_media_type
CHECK (media_type IN ('IMAGE', 'VIDEO', 'REEL'));
