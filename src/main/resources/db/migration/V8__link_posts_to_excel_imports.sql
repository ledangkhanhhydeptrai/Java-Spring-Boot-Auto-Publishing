
-- V8: Link posts to Excel imports
-- Existing posts remain unchanged.

ALTER TABLE posts
    ADD COLUMN import_id UUID,
    ADD COLUMN source_row_number INTEGER;

ALTER TABLE posts
    ADD CONSTRAINT fk_posts_excel_import
    FOREIGN KEY (import_id)
    REFERENCES excel_imports(id);

ALTER TABLE posts
    ADD CONSTRAINT ck_posts_source_row_number
    CHECK (
        source_row_number IS NULL
        OR source_row_number > 0
    );

CREATE INDEX idx_posts_import_id
    ON posts(import_id);

CREATE INDEX idx_posts_import_row
    ON posts(import_id, source_row_number);
