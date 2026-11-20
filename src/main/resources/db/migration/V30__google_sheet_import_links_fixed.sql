-- V30: Link Google Sheets connections to Excel imports.
-- Safe for PostgreSQL. No hardcoded workspace table name or user IDs.
-- Both composite FKs enforce that the connection and import belong to the same workspace.

ALTER TABLE public.google_sheet_connections
    ADD CONSTRAINT uq_gsc_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE public.excel_imports
    ADD CONSTRAINT uq_excel_imports_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE public.google_sheet_import_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    import_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_gsil_connection_workspace
        FOREIGN KEY (connection_id, workspace_id)
        REFERENCES public.google_sheet_connections(id, workspace_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_gsil_import_workspace
        FOREIGN KEY (import_id, workspace_id)
        REFERENCES public.excel_imports(id, workspace_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_gsil_connection_import UNIQUE (connection_id, import_id),
    CONSTRAINT uq_gsil_import UNIQUE (import_id)
);

CREATE INDEX idx_gsil_enabled_connection
    ON public.google_sheet_import_links (connection_id, import_id)
    WHERE enabled = TRUE;
