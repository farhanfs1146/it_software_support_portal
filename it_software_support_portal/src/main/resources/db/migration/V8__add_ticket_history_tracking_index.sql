-- RECONSTRUCTED MIGRATION - see docs/MIGRATIONS.md before running this against an existing database.
--
-- The original V8__add_ticket_history_tracking_index.sql was applied to the development database
-- on 2026-09-29 (flyway_schema_history: version 8, checksum 1344533264) but was never committed
-- to any branch and is no longer on disk. Its exact byte content could not be recovered, so this
-- file reconstructs the migration from the effect it actually had on the schema rather than from
-- its original text. The DDL below is taken verbatim from the surviving index definition in the
-- development database:
--
--   CREATE INDEX idx_ticket_history_tracking_ticket_changed_at
--     ON it_software_support_db.ticket_history_tracking USING btree (ticket_id, changed_at DESC)
--
-- Because the text differs from the original, this file's checksum differs from the recorded
-- 1344533264. Any database that already applied the original V8 therefore needs a one-time
-- reconciliation step before it will start again - docs/MIGRATIONS.md documents the two options.
-- Fresh databases (including every Testcontainers test run and CI) are unaffected and get the
-- correct schema directly.
--
-- Purpose of the index: supports TicketHistoryTrackingRepository.findByTicketIdOrderByChangedAtDesc,
-- which filters by ticket_id and orders by changed_at descending.
--
-- IF NOT EXISTS is used defensively so the migration is idempotent on any database where the index
-- is already present but unrecorded.

CREATE INDEX IF NOT EXISTS idx_ticket_history_tracking_ticket_changed_at
    ON ticket_history_tracking (ticket_id, changed_at DESC);
