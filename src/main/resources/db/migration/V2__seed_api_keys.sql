-- Seed test API keys for LOCAL DEVELOPMENT ONLY.
-- ⚠️  WARNING: These keys are committed to source control and MUST be rotated before
--              any non-local deployment. Run the admin API (POST /llm/admin/keys) to
--              create production keys and DELETE these rows immediately afterward.
-- Key hashes are computed inline using PostgreSQL's pgcrypto.
-- Raw keys (dev only — NEVER use these in staging or production):
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
