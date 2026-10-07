-- Database-backed ticket number generation (audit finding P0-5).
--
-- The previous scheme was "TKT-" + System.currentTimeMillis(), which has no uniqueness guarantee:
-- 200 simultaneous calls produced only 68 distinct values (185 colliding), and every collision
-- beyond the first violates tickets_ticket_number_key and surfaces as HTTP 500.
--
-- A PostgreSQL sequence is the correct mechanism here:
--   * nextval() is atomic and never returns the same value twice, even under full concurrency;
--   * it is explicitly non-transactional, so a rolled-back ticket creation does not reissue its
--     number - gaps are acceptable, duplicates are not;
--   * it needs no application-level locking, no synchronized block and no retry loop.
--
-- Format: the external shape "TKT-" + digits is preserved; only the digits' meaning changes, from an
-- epoch-millisecond value to a zero-padded sequence value (TKT-00000001). Existing rows are left
-- untouched and both forms match the same TKT-\d+ pattern, so no data migration is required.
-- tickets_ticket_number_key remains as the final backstop: if a number ever did repeat, the insert
-- would fail loudly rather than corrupt data silently.

CREATE SEQUENCE IF NOT EXISTS ticket_number_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;
