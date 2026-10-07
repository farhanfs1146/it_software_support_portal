-- Session lifecycle: refresh tokens and immediate access-token revocation (Phase 7).
--
-- Phases 4 and 5 gave the application authentication, authorization and login abuse protection, but
-- no way to END a session. Two concrete defects were recorded as known limitations:
--
--   1. There was no refresh flow, so a client had to re-submit the password every 30 minutes - which
--      pushes applications towards caching the password, the opposite of what short tokens are for.
--   2. An access token could not be revoked. Deactivating a user, or changing a password, left every
--      already-issued token valid until it expired. For a compromised credential the window in which
--      the attacker keeps full access was the whole token TTL.
--
-- This migration adds the state both fixes need.
--
-- ---------------------------------------------------------------------------------------------
-- users.token_version - the revocation mechanism
-- ---------------------------------------------------------------------------------------------
-- A monotonically increasing counter. Every issued access token carries the value current at the
-- time of issue in a "tv" claim; the resource server compares the claim against this column and
-- rejects any mismatch. Incrementing the column therefore invalidates every outstanding token for
-- that user at once, with no per-token bookkeeping and no denylist to grow without bound.
--
-- It is a counter rather than a "revoked before" timestamp on purpose: a counter needs no clock
-- agreement between the issuer and the verifier, and cannot be defeated by clock skew or by two
-- revocations landing inside the same clock tick.
--
-- NOT NULL DEFAULT 0 so existing rows are valid immediately and no backfill is needed. Tokens
-- minted before this migration carry no "tv" claim at all and are rejected as malformed, which is
-- the fail-closed direction and costs at most one re-login.
--
-- ---------------------------------------------------------------------------------------------
-- refresh_tokens - long-lived credentials, stored as hashes
-- ---------------------------------------------------------------------------------------------
-- WHY A TABLE AND NOT A SECOND JWT. A refresh token must be revocable the moment it is used twice.
-- A self-contained JWT cannot be, because nothing is recorded server-side to compare against; the
-- whole point of the row is that the server holds authoritative state about this one credential.
--
-- WHAT IS STORED. token_hash holds a SHA-256 hash, hex-encoded, of the opaque token handed to the
-- client. The plaintext token exists only in the HTTP response and is never written anywhere.
--   * Hashed, so a database disclosure does not yield usable session credentials.
--   * SHA-256 and not BCrypt, deliberately: the token is 256 bits of output from a CSPRNG, not a
--     human-chosen password, so there is no low-entropy guess space for a slow hash to defend. A
--     fast deterministic hash is also what makes the unique-index lookup below possible at all -
--     BCrypt salts each hash, so finding a token would mean scanning every row.
--   * CHAR-width VARCHAR(64) is exactly hex-encoded SHA-256.
--
-- family_id GROUPS A ROTATION CHAIN. Each login starts a family. Every refresh revokes the token it
-- was presented with and issues a successor in the same family. If a token that has already been
-- rotated is presented again, that is either a replay of a stolen token or a stolen token being used
-- after the legitimate client already rotated - the two are indistinguishable from here, so the
-- entire family is revoked and both parties are forced to re-authenticate. Without a family id,
-- detection would only ever revoke the one leaked token and the thief's successor would survive.
--
-- revoked_at / revoked_reason: rows are revoked, not deleted, so reuse detection can tell "this
-- token was rotated" from "this token never existed" and the reason is available for investigation.
-- A scheduled job deletes rows once they are expired and past the retention window.
--
-- TIMESTAMP, NOT TIMESTAMPTZ: this matches every other timestamp column in this schema
-- (tickets.created_at, ticket_history_tracking.changed_at). The application writes all of them in
-- UTC. Introducing a second timestamp convention for one table would be a worse trade than keeping
-- one convention the whole schema shares.
--
-- ON DELETE CASCADE is used here, and is the only cascade in the schema. V11 chose RESTRICT
-- everywhere on the grounds that deleting a user must not silently erase their tickets or their
-- audit trail. The opposite is true for session credentials: they are derived authentication state,
-- not business history, and a deleted user's live sessions must not outlive the account. RESTRICT
-- would also make deleting a user impossible while any token row remained.

ALTER TABLE users
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.token_version IS
    'Incremented to invalidate every outstanding access token for this user. Access tokens carry the value current at issue time in a "tv" claim; a mismatch is rejected.';

CREATE TABLE refresh_tokens
(
    id             BIGSERIAL PRIMARY KEY,
    token_hash     VARCHAR(64) NOT NULL UNIQUE,
    user_id        BIGINT      NOT NULL,
    family_id      VARCHAR(36) NOT NULL,
    issued_at      TIMESTAMP   NOT NULL,
    expires_at     TIMESTAMP   NOT NULL,
    revoked_at     TIMESTAMP,
    revoked_reason VARCHAR(40),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

COMMENT ON COLUMN refresh_tokens.token_hash IS
    'Hex-encoded SHA-256 of the opaque refresh token. The plaintext is never stored.';
COMMENT ON COLUMN refresh_tokens.family_id IS
    'Groups one rotation chain. Presenting an already-rotated token revokes the whole family.';

-- Lookup by hash is the hot path on every refresh; the UNIQUE constraint above already provides it,
-- so no second index is created for it.

-- "Revoke every live session for this user" (deactivation, password change, logout-everywhere) and
-- "how many sessions does this user have" both filter on user_id and revoked_at IS NULL. A partial
-- index keeps only live rows, so it does not grow with historical, already-revoked sessions.
CREATE INDEX idx_refresh_tokens_user_live
    ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

-- Reuse detection revokes a whole family in one statement, keyed on family_id.
CREATE INDEX idx_refresh_tokens_family
    ON refresh_tokens (family_id);

-- The scheduled purge deletes by expires_at.
CREATE INDEX idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);
