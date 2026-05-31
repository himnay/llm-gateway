-- Seed test API keys for local development.
-- Key hashes are computed inline using PostgreSQL's pgcrypto.
-- Raw keys (for local use only — rotate in production):
--   Dev key  : llm-gateway-dev-key-2026
--   Admin key: llm-gateway-admin-key-2026

INSERT INTO api_keys (name, key_hash, client_id, enabled)
VALUES
    (
        'Development Key',
        encode(digest('llm-gateway-dev-key-2026', 'sha256'), 'hex'),
        'dev-client',
        TRUE
    ),
    (
        'Admin Key',
        encode(digest('llm-gateway-admin-key-2026', 'sha256'), 'hex'),
        'admin-client',
        TRUE
    );
