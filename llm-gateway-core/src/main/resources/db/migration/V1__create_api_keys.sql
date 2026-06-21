-- Enable pgcrypto for SHA-256 hashing of API keys
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE api_keys (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    key_hash    CHAR(64)      NOT NULL,
    client_id   VARCHAR(100),
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,
    last_used   TIMESTAMPTZ,
    CONSTRAINT api_keys_hash_unique UNIQUE (key_hash)
);

-- Partial index: only active, non-expired keys — optimises per-request auth lookup
CREATE INDEX idx_api_keys_active
    ON api_keys (key_hash)
    WHERE enabled = TRUE;

COMMENT ON TABLE  api_keys IS 'Gateway API key registry. key_hash stores SHA-256(raw_key) in hex.';
COMMENT ON COLUMN api_keys.key_hash IS 'Hex-encoded SHA-256 of the raw API key. Raw key is never stored.';
COMMENT ON COLUMN api_keys.expires_at IS 'NULL = never expires.';
