-- Persistent audit log for all LLM gateway requests.
-- Used for compliance, billing reconciliation, and per-client analytics.
-- Prompt text is never stored — only its SHA-256 hash for de-duplication.

CREATE TABLE IF NOT EXISTS request_log (
    id               BIGSERIAL     PRIMARY KEY,
    request_id       VARCHAR(64)   NOT NULL,
    correlation_id   VARCHAR(64),
    provider         VARCHAR(50)   NOT NULL,
    model            VARCHAR(100),
    client_id        VARCHAR(100),
    prompt_hash      CHAR(64),          -- SHA-256 of the sanitized prompt (never the raw prompt)
    prompt_length    INT,
    cache_hit        BOOLEAN       NOT NULL DEFAULT FALSE,
    latency_ms       BIGINT,
    prompt_tokens    INT,
    completion_tokens INT,
    total_tokens     INT,
    error            TEXT,
    sanitized        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_request_log_created   ON request_log (created_at DESC);
CREATE INDEX idx_request_log_provider  ON request_log (provider, created_at DESC);
CREATE INDEX idx_request_log_client    ON request_log (client_id, created_at DESC);
CREATE INDEX idx_request_log_req_id    ON request_log (request_id);

COMMENT ON TABLE  request_log IS 'Gateway request audit log. Raw prompts are never stored.';
COMMENT ON COLUMN request_log.prompt_hash IS 'SHA-256 hex of the sanitized prompt — used for cache analysis, never for recovery.';
