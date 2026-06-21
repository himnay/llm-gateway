-- Gateway authentication moved from custom X-API-Key lookups to Keycloak-issued OAuth2 JWTs
-- (see SecurityConfig). The api_keys table and its admin CRUD endpoints are gone — identity
-- now lives entirely in Keycloak, not in this database.
DROP TABLE IF EXISTS api_keys;
