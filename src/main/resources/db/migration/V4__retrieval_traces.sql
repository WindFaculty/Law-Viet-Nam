-- Phase 4: retrieval trace for reproducing a local RAG decision.
-- One row per local processing attempt; candidate scores stay as JSON text so
-- the exact ranking can be inspected without depending on a JPA mapping.
CREATE TABLE retrieval_traces (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    question text NOT NULL,
    applicable_date date NOT NULL,
    model_id varchar(100) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    corpus_version varchar(160) NOT NULL,
    embedding_space_id varchar(100) NOT NULL,
    gate_passed boolean NOT NULL,
    max_cosine double precision NOT NULL,
    decision varchar(32) NOT NULL,
    reason_code varchar(32) NOT NULL,
    candidate_scores text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_retrieval_traces_message
        FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_retrieval_traces_cosine CHECK (max_cosine >= -1.000001 AND max_cosine <= 1.000001)
);

CREATE INDEX idx_retrieval_traces_message ON retrieval_traces (message_id, created_at);
CREATE INDEX idx_retrieval_traces_decision ON retrieval_traces (decision, reason_code);
