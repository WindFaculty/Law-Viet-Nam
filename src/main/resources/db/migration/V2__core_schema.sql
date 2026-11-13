-- Phase 2 core schema for the HITL RAG legal assistant.
-- Timestamps are timestamptz; quota periods and applicable dates are Asia/Ho_Chi_Minh
-- calendar dates (the application resolves the zone before persisting).
-- V1 already created the pgvector extension.

-- ---------------------------------------------------------------------------
-- 1. Chat sessions: one owner, either an account or an anonymous client id.
-- ---------------------------------------------------------------------------
CREATE TABLE chat_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id varchar(100),
    anon_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_chat_sessions_owner CHECK (
        (owner_user_id IS NOT NULL AND anon_id IS NULL)
        OR (owner_user_id IS NULL AND anon_id IS NOT NULL)
    )
);

CREATE INDEX idx_chat_sessions_anon_id ON chat_sessions (anon_id);
CREATE INDEX idx_chat_sessions_owner_user_id ON chat_sessions (owner_user_id);
CREATE INDEX idx_chat_sessions_expires_at ON chat_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- 2. Immutable source snapshots: raw bytes (or storage path) plus SHA-256.
-- ---------------------------------------------------------------------------
CREATE TABLE source_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_url text NOT NULL,
    content text,
    storage_path text,
    content_sha256 varchar(64) NOT NULL,
    fetched_at timestamptz NOT NULL,
    content_type varchar(100) NOT NULL,
    parser_version varchar(50) NOT NULL,
    byte_size bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_source_snapshots_payload CHECK (content IS NOT NULL OR storage_path IS NOT NULL),
    CONSTRAINT ck_source_snapshots_sha256 CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_snapshots_byte_size CHECK (byte_size IS NULL OR byte_size >= 0),
    CONSTRAINT uq_source_snapshots_dedupe UNIQUE (source_url, content_sha256, parser_version)
);

CREATE INDEX idx_source_snapshots_fetched_at ON source_snapshots (fetched_at);

-- ---------------------------------------------------------------------------
-- 3. Legal documents: one row per source version of the same document_key.
-- ---------------------------------------------------------------------------
CREATE TABLE legal_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_key varchar(160) NOT NULL,
    title text NOT NULL,
    doc_number varchar(100),
    issuing_body varchar(255),
    issue_date date,
    effective_from date,
    effective_to date,
    effective_status varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    source_url text NOT NULL,
    snapshot_id uuid NOT NULL,
    verification_status varchar(32) NOT NULL DEFAULT 'QUARANTINED',
    published_at timestamptz,
    last_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_legal_documents_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES source_snapshots (id),
    CONSTRAINT ck_legal_documents_effective_status
        CHECK (effective_status IN ('EFFECTIVE', 'UPCOMING', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_legal_documents_verification_status
        CHECK (verification_status IN ('QUARANTINED', 'READY', 'ARCHIVED', 'REJECTED')),
    CONSTRAINT ck_legal_documents_effective_range
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    -- A document may be QUARANTINED with unknown metadata, but READY requires verified metadata.
    CONSTRAINT ck_legal_documents_ready_metadata CHECK (
        verification_status <> 'READY'
        OR (
            doc_number IS NOT NULL AND btrim(doc_number) <> ''
            AND issuing_body IS NOT NULL AND btrim(issuing_body) <> ''
            AND issue_date IS NOT NULL
            AND effective_status <> 'UNKNOWN'
            AND effective_from IS NOT NULL
            AND btrim(source_url) <> ''
        )
    ),
    CONSTRAINT uq_legal_documents_version UNIQUE (document_key, snapshot_id)
);

CREATE INDEX idx_legal_documents_document_key ON legal_documents (document_key);
CREATE INDEX idx_legal_documents_doc_number ON legal_documents (doc_number);
CREATE INDEX idx_legal_documents_verification ON legal_documents (verification_status);
CREATE INDEX idx_legal_documents_effective ON legal_documents (effective_from, effective_to);
CREATE INDEX idx_legal_documents_snapshot ON legal_documents (snapshot_id);

-- ---------------------------------------------------------------------------
-- 4. Legal articles: Dieu / Khoan / Diem tree with verbatim text and FTS.
-- ---------------------------------------------------------------------------
CREATE TABLE legal_articles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL,
    parent_id uuid,
    hierarchy_level varchar(16) NOT NULL,
    article_code varchar(64) NOT NULL,
    article_number varchar(32) NOT NULL,
    title text,
    raw_text text NOT NULL,
    search_text text NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, coalesce(title, '') || ' ' || search_text)
    ) STORED,
    locator varchar(255) NOT NULL,
    scope varchar(255),
    effective_from date,
    effective_to date,
    verification_status varchar(32) NOT NULL DEFAULT 'QUARANTINED',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_legal_articles_document
        FOREIGN KEY (document_id) REFERENCES legal_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_legal_articles_parent
        FOREIGN KEY (parent_id) REFERENCES legal_articles (id) ON DELETE CASCADE,
    CONSTRAINT ck_legal_articles_hierarchy_level
        CHECK (hierarchy_level IN ('DIEU', 'KHOAN', 'DIEM')),
    CONSTRAINT ck_legal_articles_verification_status
        CHECK (verification_status IN ('QUARANTINED', 'READY', 'ARCHIVED', 'REJECTED')),
    CONSTRAINT ck_legal_articles_effective_range
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_legal_articles_parent_not_self
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT uq_legal_articles_code UNIQUE (document_id, article_code)
);

CREATE INDEX idx_legal_articles_document ON legal_articles (document_id);
CREATE INDEX idx_legal_articles_parent ON legal_articles (parent_id);
CREATE INDEX idx_legal_articles_verification ON legal_articles (verification_status);
CREATE INDEX idx_legal_articles_effective ON legal_articles (effective_from, effective_to);
CREATE INDEX idx_legal_articles_search_vector ON legal_articles USING gin (search_vector);

-- ---------------------------------------------------------------------------
-- 5. Legal document relations: amendment / replacement / repeal.
-- ---------------------------------------------------------------------------
CREATE TABLE legal_document_relations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_document_id uuid NOT NULL,
    target_document_id uuid NOT NULL,
    source_article_id uuid,
    target_article_id uuid,
    relation_type varchar(32) NOT NULL,
    verification_status varchar(32) NOT NULL DEFAULT 'QUARANTINED',
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_legal_relations_source_document
        FOREIGN KEY (source_document_id) REFERENCES legal_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_legal_relations_target_document
        FOREIGN KEY (target_document_id) REFERENCES legal_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_legal_relations_source_article
        FOREIGN KEY (source_article_id) REFERENCES legal_articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_legal_relations_target_article
        FOREIGN KEY (target_article_id) REFERENCES legal_articles (id) ON DELETE CASCADE,
    CONSTRAINT ck_legal_relations_type
        CHECK (relation_type IN ('AMENDMENT', 'REPLACEMENT', 'REPEAL')),
    CONSTRAINT ck_legal_relations_verification_status
        CHECK (verification_status IN ('QUARANTINED', 'READY', 'ARCHIVED', 'REJECTED')),
    CONSTRAINT ck_legal_relations_not_self
        CHECK (source_document_id <> target_document_id)
);

CREATE UNIQUE INDEX uq_legal_document_relations_identity ON legal_document_relations (
    source_document_id,
    target_document_id,
    relation_type,
    coalesce(source_article_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(target_article_id, '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE INDEX idx_legal_relations_target_document ON legal_document_relations (target_document_id);
CREATE INDEX idx_legal_relations_source_article ON legal_document_relations (source_article_id);
CREATE INDEX idx_legal_relations_target_article ON legal_document_relations (target_article_id);

-- ---------------------------------------------------------------------------
-- 6. Article embeddings: locked model/space, 768 dimensions, HNSW cosine index.
-- ---------------------------------------------------------------------------
CREATE TABLE article_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id uuid NOT NULL,
    chunk_index integer NOT NULL DEFAULT 0,
    model_id varchar(100) NOT NULL,
    embedding_space_id varchar(100) NOT NULL,
    dimensions integer NOT NULL DEFAULT 768,
    text_hash varchar(64) NOT NULL,
    embedding vector(768) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_article_embeddings_article
        FOREIGN KEY (article_id) REFERENCES legal_articles (id) ON DELETE CASCADE,
    CONSTRAINT ck_article_embeddings_model CHECK (model_id = 'gemini-embedding-001'),
    CONSTRAINT ck_article_embeddings_dimensions CHECK (dimensions = 768),
    CONSTRAINT ck_article_embeddings_space CHECK (btrim(embedding_space_id) <> ''),
    CONSTRAINT ck_article_embeddings_chunk CHECK (chunk_index >= 0),
    CONSTRAINT ck_article_embeddings_text_hash CHECK (text_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_article_embeddings_status
        CHECK (status IN ('PENDING', 'READY', 'STALE', 'FAILED')),
    CONSTRAINT uq_article_embeddings_identity
        UNIQUE (article_id, model_id, embedding_space_id, chunk_index, text_hash)
);

CREATE INDEX idx_article_embeddings_embedding_hnsw
    ON article_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_article_embeddings_status ON article_embeddings (status);
CREATE INDEX idx_article_embeddings_space ON article_embeddings (embedding_space_id, status);

-- ---------------------------------------------------------------------------
-- 7. Chat messages: AI lifecycle, consent binding and optimistic locking.
-- ---------------------------------------------------------------------------
CREATE TABLE chat_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    sender varchar(16) NOT NULL,
    in_reply_to_id uuid,
    idempotency_key varchar(128),
    content text NOT NULL DEFAULT '',
    message_status varchar(32) NOT NULL,
    outcome varchar(32),
    suggested_query text,
    applicable_date date NOT NULL,
    question_hash varchar(64),
    confirm_expires_at timestamptz,
    confirmed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_messages_in_reply_to
        FOREIGN KEY (in_reply_to_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_messages_sender CHECK (sender IN ('USER', 'AI')),
    CONSTRAINT ck_chat_messages_status CHECK (message_status IN (
        'PROCESSING_LOCAL',
        'AWAITING_WEB_CONFIRM',
        'WEB_QUEUED',
        'WEB_SEARCHING',
        'COMPLETED',
        'CANCELLED',
        'EXPIRED',
        'NO_EVIDENCE',
        'WEB_FAILED',
        'FAILED',
        'NEEDS_CLARIFICATION'
    )),
    CONSTRAINT ck_chat_messages_outcome CHECK (outcome IS NULL OR outcome IN (
        'ANSWERED',
        'AWAITING_WEB_CONFIRM',
        'NEEDS_CLARIFICATION',
        'CANCELLED',
        'EXPIRED',
        'NO_EVIDENCE',
        'WEB_FAILED',
        'FAILED'
    )),
    CONSTRAINT ck_chat_messages_user_shape
        CHECK (sender <> 'USER' OR in_reply_to_id IS NULL),
    CONSTRAINT ck_chat_messages_ai_shape
        CHECK (sender <> 'AI' OR in_reply_to_id IS NOT NULL),
    CONSTRAINT ck_chat_messages_question_hash
        CHECK (question_hash IS NULL OR question_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chat_messages_idempotency_key
        CHECK (idempotency_key IS NULL OR length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_chat_messages_confirm_time
        CHECK (confirmed_at IS NULL OR (confirm_expires_at IS NOT NULL AND confirmed_at <= confirm_expires_at)),
    -- A completed AI answer must have durable content (citations are validated in Phase 4).
    CONSTRAINT ck_chat_messages_completed_content
        CHECK (sender <> 'AI' OR message_status <> 'COMPLETED' OR btrim(content) <> ''),
    CONSTRAINT uq_chat_messages_idempotency UNIQUE (session_id, idempotency_key)
);

CREATE INDEX idx_chat_messages_session_created ON chat_messages (session_id, created_at);
CREATE INDEX idx_chat_messages_status ON chat_messages (message_status);
CREATE INDEX idx_chat_messages_in_reply_to ON chat_messages (in_reply_to_id);
CREATE INDEX idx_chat_messages_awaiting_expiry
    ON chat_messages (confirm_expires_at)
    WHERE message_status = 'AWAITING_WEB_CONFIRM';

-- ---------------------------------------------------------------------------
-- 8. Message citations: verbatim snapshot + precise legal locator.
-- ---------------------------------------------------------------------------
CREATE TABLE message_citations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    article_id uuid NOT NULL,
    source_snapshot_id uuid NOT NULL,
    citation_order integer NOT NULL,
    excerpt_start integer NOT NULL,
    excerpt_end integer NOT NULL,
    locator varchar(255) NOT NULL,
    verbatim_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_message_citations_message
        FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_citations_document
        FOREIGN KEY (document_version_id) REFERENCES legal_documents (id),
    CONSTRAINT fk_message_citations_article
        FOREIGN KEY (article_id) REFERENCES legal_articles (id),
    CONSTRAINT fk_message_citations_snapshot
        FOREIGN KEY (source_snapshot_id) REFERENCES source_snapshots (id),
    CONSTRAINT ck_message_citations_order CHECK (citation_order >= 0),
    CONSTRAINT ck_message_citations_excerpt CHECK (excerpt_start >= 0 AND excerpt_end > excerpt_start),
    CONSTRAINT ck_message_citations_verbatim CHECK (btrim(verbatim_text) <> ''),
    CONSTRAINT uq_message_citations_order UNIQUE (message_id, citation_order)
);

CREATE INDEX idx_message_citations_message ON message_citations (message_id);
CREATE INDEX idx_message_citations_article ON message_citations (article_id);
CREATE INDEX idx_message_citations_document ON message_citations (document_version_id);

-- ---------------------------------------------------------------------------
-- 9. Durable web search jobs: leases, heartbeat and one active job per message.
-- ---------------------------------------------------------------------------
CREATE TABLE web_search_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    job_status varchar(32) NOT NULL DEFAULT 'QUEUED',
    attempt_count integer NOT NULL DEFAULT 0,
    stage varchar(32) NOT NULL DEFAULT 'QUEUED',
    lease_token uuid,
    lease_owner varchar(100),
    lease_expires_at timestamptz,
    heartbeat_at timestamptz,
    deadline timestamptz NOT NULL,
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_web_search_jobs_message
        FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_web_search_jobs_status CHECK (job_status IN (
        'QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT ck_web_search_jobs_stage CHECK (stage IN (
        'QUEUED', 'CLAIM', 'SEARCH', 'FETCH', 'SNAPSHOT', 'PARSE',
        'VALIDATE', 'EMBED', 'PUBLISH', 'RETRIEVE_AGAIN', 'FINALIZE'
    )),
    CONSTRAINT ck_web_search_jobs_attempts CHECK (attempt_count >= 0 AND attempt_count <= 2),
    -- Lease fields are all present or all absent.
    CONSTRAINT ck_web_search_jobs_lease CHECK (
        (lease_token IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_web_search_jobs_heartbeat CHECK (heartbeat_at IS NULL OR lease_token IS NOT NULL)
);

CREATE UNIQUE INDEX uq_web_search_jobs_active_per_message
    ON web_search_jobs (message_id)
    WHERE job_status IN ('QUEUED', 'CLAIMED', 'RUNNING', 'RETRY_WAIT');
CREATE INDEX idx_web_search_jobs_lease ON web_search_jobs (job_status, lease_expires_at);
CREATE INDEX idx_web_search_jobs_deadline ON web_search_jobs (deadline);

-- ---------------------------------------------------------------------------
-- 10. Quota buckets: one bucket per subject per Asia/Ho_Chi_Minh day.
-- ---------------------------------------------------------------------------
CREATE TABLE quota_buckets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type varchar(16) NOT NULL,
    anon_id varchar(64),
    owner_user_id varchar(100),
    quota_period date NOT NULL,
    limit_count integer NOT NULL DEFAULT 5,
    used_count integer NOT NULL DEFAULT 0,
    reserved_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_quota_buckets_subject CHECK (
        (subject_type = 'ANON' AND anon_id IS NOT NULL AND owner_user_id IS NULL)
        OR (subject_type = 'USER' AND owner_user_id IS NOT NULL AND anon_id IS NULL)
    ),
    CONSTRAINT ck_quota_buckets_limit CHECK (limit_count >= 0),
    CONSTRAINT ck_quota_buckets_used CHECK (used_count >= 0),
    CONSTRAINT ck_quota_buckets_reserved CHECK (reserved_count >= 0),
    CONSTRAINT ck_quota_buckets_capacity CHECK (used_count + reserved_count <= limit_count)
);

CREATE UNIQUE INDEX uq_quota_buckets_period_anon
    ON quota_buckets (quota_period, anon_id)
    WHERE anon_id IS NOT NULL;
CREATE UNIQUE INDEX uq_quota_buckets_period_owner
    ON quota_buckets (quota_period, owner_user_id)
    WHERE owner_user_id IS NOT NULL;
CREATE INDEX idx_quota_buckets_period ON quota_buckets (quota_period);

-- ---------------------------------------------------------------------------
-- 11. Quota reservations: held capacity for one message/processing attempt.
-- ---------------------------------------------------------------------------
CREATE TABLE quota_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id uuid NOT NULL,
    message_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    idempotency_key varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    committed_at timestamptz,
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_quota_reservations_bucket
        FOREIGN KEY (bucket_id) REFERENCES quota_buckets (id) ON DELETE CASCADE,
    CONSTRAINT fk_quota_reservations_message
        FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_quota_reservations_status
        CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_quota_reservations_key
        CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_quota_reservations_committed
        CHECK (status <> 'COMMITTED' OR committed_at IS NOT NULL),
    CONSTRAINT ck_quota_reservations_released
        CHECK (status <> 'RELEASED' OR released_at IS NOT NULL),
    CONSTRAINT uq_quota_reservations_idempotency
        UNIQUE (message_id, idempotency_key)
);

CREATE UNIQUE INDEX uq_quota_reservations_active_per_message
    ON quota_reservations (message_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_quota_reservations_bucket_status
    ON quota_reservations (bucket_id, status);
CREATE INDEX idx_quota_reservations_expiry
    ON quota_reservations (status, expires_at);

-- ---------------------------------------------------------------------------
-- 12. Quota ledger: append-only, unique per answer message.
-- ---------------------------------------------------------------------------
CREATE TABLE quota_ledger (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    answer_message_id uuid NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    quota_period date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_quota_ledger_bucket
        FOREIGN KEY (bucket_id) REFERENCES quota_buckets (id),
    CONSTRAINT fk_quota_ledger_reservation
        FOREIGN KEY (reservation_id) REFERENCES quota_reservations (id),
    CONSTRAINT fk_quota_ledger_answer
        FOREIGN KEY (answer_message_id) REFERENCES chat_messages (id),
    CONSTRAINT ck_quota_ledger_quantity CHECK (quantity > 0),
    CONSTRAINT uq_quota_ledger_answer_message UNIQUE (answer_message_id),
    CONSTRAINT uq_quota_ledger_reservation UNIQUE (reservation_id)
);

CREATE INDEX idx_quota_ledger_bucket_period ON quota_ledger (bucket_id, quota_period);

-- A ledger row records real consumption; it must never be rewritten or removed.
CREATE FUNCTION reject_quota_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'quota_ledger is append-only';
END;
$$;

CREATE TRIGGER trg_quota_ledger_immutable
    BEFORE UPDATE OR DELETE ON quota_ledger
    FOR EACH ROW EXECUTE FUNCTION reject_quota_ledger_mutation();
