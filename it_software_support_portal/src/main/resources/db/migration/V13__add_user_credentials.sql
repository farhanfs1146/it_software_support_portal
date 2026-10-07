-- Credential storage for database-backed authentication (Phase 4).
--
-- Until now the users table had no credential column at all: the application had no authentication,
-- and Phase 2's transitional identity mechanism trusted an X-User-Id request header. Phase 4 replaces
-- that with real authentication, which requires somewhere to keep a verifier.
--
-- NULLABLE ON PURPOSE, and it is a security property rather than laziness:
--   * Users that already exist have no password and therefore cannot authenticate. That is the
--     correct fail-closed outcome - enabling authentication must not hand everyone a usable account.
--   * A password is set explicitly, either by an administrator through POST /api/users or by the
--     one-time bootstrap administrator described in docs/SECURITY.md.
--   * The login flow treats a NULL hash as "no password set" and fails authentication, with the same
--     generic response as a wrong password so it reveals nothing about the account.
--
-- The column stores a BCrypt hash produced by Spring Security's PasswordEncoder - never a plaintext
-- password and never a reversibly encrypted one. BCrypt hashes are 60 characters; the column is
-- deliberately wider so the encoder's algorithm can be upgraded (Spring Security's delegating
-- encoder prefixes hashes with {id}) without another migration.
--
-- No index is added: authentication looks users up by email, which already has a unique index from
-- V1 (users_email_key).

ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(255);

COMMENT ON COLUMN users.password_hash IS
    'BCrypt hash of the user password. NULL means no password set, so the user cannot authenticate.';
