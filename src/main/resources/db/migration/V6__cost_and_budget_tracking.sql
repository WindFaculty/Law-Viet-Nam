-- Phase 7: daily API usage and cost tracking by turn category.
-- Units are provider calls, characters/tokens and bytes, not USD: no billing
-- account exists yet (decisions.md OD-02), so fixed prices would be invented.

CREATE TABLE cost_usage_daily (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    usage_date date NOT NULL,
    category varchar(32) NOT NULL,
    embedding_calls integer NOT NULL DEFAULT 0,
    embedding_input_chars bigint NOT NULL DEFAULT 0,
    embedding_cache_hits integer NOT NULL DEFAULT 0,
    grounding_calls integer NOT NULL DEFAULT 0,
    grounding_input_tokens bigint NOT NULL DEFAULT 0,
    grounding_output_tokens bigint NOT NULL DEFAULT 0,
    web_search_calls integer NOT NULL DEFAULT 0,
    fetched_urls integer NOT NULL DEFAULT 0,
    downloaded_bytes bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cost_usage_daily_category CHECK (category IN (
        'LOCAL_ANSWERED',
        'LOCAL_AWAITING_CONFIRM',
        'LOCAL_FAILED',
        'CLARIFICATION',
        'WEB_CANCELLED',
        'WEB_COMPLETED',
        'WEB_NO_EVIDENCE',
        'WEB_FAILED'
    )),
    CONSTRAINT ck_cost_usage_daily_embedding_calls CHECK (embedding_calls >= 0),
    CONSTRAINT ck_cost_usage_daily_embedding_chars CHECK (embedding_input_chars >= 0),
    CONSTRAINT ck_cost_usage_daily_cache_hits CHECK (embedding_cache_hits >= 0),
    CONSTRAINT ck_cost_usage_daily_grounding_calls CHECK (grounding_calls >= 0),
    CONSTRAINT ck_cost_usage_daily_input_tokens CHECK (grounding_input_tokens >= 0),
    CONSTRAINT ck_cost_usage_daily_output_tokens CHECK (grounding_output_tokens >= 0),
    CONSTRAINT ck_cost_usage_daily_search_calls CHECK (web_search_calls >= 0),
    CONSTRAINT ck_cost_usage_daily_fetched_urls CHECK (fetched_urls >= 0),
    CONSTRAINT ck_cost_usage_daily_bytes CHECK (downloaded_bytes >= 0),
    CONSTRAINT uq_cost_usage_daily_category UNIQUE (usage_date, category)
);

CREATE INDEX idx_cost_usage_daily_date ON cost_usage_daily (usage_date);
