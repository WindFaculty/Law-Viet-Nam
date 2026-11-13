-- Phase 6: durable web-search worker, candidate audit and admin approvals.
-- Lease fields already exist in V2; this adds retry scheduling and the
-- append-only evidence needed to review what was discovered/approved.

ALTER TABLE web_search_jobs
    ADD COLUMN next_attempt_at timestamptz;

CREATE INDEX idx_web_search_jobs_claim
    ON web_search_jobs (job_status, next_attempt_at, lease_expires_at);

-- One row per provider result. Snippets/titles are intentionally not stored:
-- they are URL-discovery hints, never legal evidence.
CREATE TABLE web_search_candidates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid NOT NULL,
    query text NOT NULL,
    provider varchar(100) NOT NULL,
    candidate_url text NOT NULL,
    rank integer NOT NULL,
    accepted boolean NOT NULL DEFAULT false,
    rejection_reason varchar(64),
    result_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_web_search_candidates_job
        FOREIGN KEY (job_id) REFERENCES web_search_jobs (id) ON DELETE CASCADE,
    CONSTRAINT ck_web_search_candidates_rank CHECK (rank >= 1),
    CONSTRAINT ck_web_search_candidates_rejected_reason
        CHECK (accepted OR rejection_reason IS NOT NULL)
);

CREATE INDEX idx_web_search_candidates_job
    ON web_search_candidates (job_id, rank, created_at);

-- Admin actions must remain auditable after the document row changes.
CREATE TABLE admin_audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor varchar(255) NOT NULL,
    action varchar(64) NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid,
    details text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_log_created_at
    ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_log_entity
    ON admin_audit_log (entity_type, entity_id);
