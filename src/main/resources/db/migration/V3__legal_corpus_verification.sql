-- Phase 3: publication verification metadata for the legal corpus.
-- Unknown fields stay false/NULL; they are never filled by inference.
ALTER TABLE legal_documents
    ADD COLUMN metadata_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN effective_dates_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN source_checked_by varchar(255),
    ADD COLUMN source_checked_at timestamptz,
    ADD COLUMN verification_notes text;

-- A READY row must have been explicitly verified; the base READY-metadata check
-- in V2 still enforces doc number, issuing body, issue date and effective date.
ALTER TABLE legal_documents
    ADD CONSTRAINT ck_legal_documents_ready_verified CHECK (
        verification_status <> 'READY'
        OR (metadata_verified AND effective_dates_verified)
    );

CREATE INDEX idx_legal_documents_published
    ON legal_documents (verification_status)
    WHERE verification_status = 'READY';
