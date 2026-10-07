-- Optimistic locking for the Ticket aggregate (audit finding P1-4).
--
-- Why only tickets: Ticket is the only entity the application mutates after creation, and the only
-- one shown to be subject to concurrent writes (the audit measured 6 concurrent status writes all
-- returning 200, last-writer-wins). users and applications are created/updated through single-writer
-- admin operations, and ticket_history_tracking is append-only, so neither needs a version column.
-- A version column is not added to every entity on principle - only where concurrent modification
-- of the same row is a real scenario.
--
-- DEFAULT 0 lets the column be NOT NULL while backfilling any pre-existing rows. Hibernate manages
-- the value from here on via @Version on Ticket.version.

ALTER TABLE tickets
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
