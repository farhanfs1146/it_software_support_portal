-- Indexes for the paged/filtered ticket list (audit finding P1-3).
--
-- Every index below was built against 20,000 realistic tickets, measured with EXPLAIN (ANALYZE),
-- and kept only because the planner actually chose it and the buffer count dropped. A fifth
-- candidate was built, measured, used zero times and discarded - see the end of this file.
--
-- Before any of these, every ticket-list query read the whole table to return 20 rows:
--   Seq Scan on tickets + top-N heapsort, 411 shared buffers.
--
--------------------------------------------------------------------------------------------------
-- 1. idx_tickets_created_at_id  (created_at DESC, id DESC)
--------------------------------------------------------------------------------------------------
-- Supports:  the default list page - ORDER BY created_at DESC, id DESC LIMIT n
--            (id is the deterministic tiebreaker that keeps offset paging stable)
-- Also used for: status and priority filters at this data volume, where the planner prefers to walk
--            this index in sort order and filter, rather than use a dedicated filter index.
-- Measured:  411 buffers -> 3 buffers; Seq Scan + top-N heapsort -> Index Scan.
-- Write cost: one more index to maintain on insert/update of created_at. Accepted: this is the
--            single most frequently executed query in the application.
CREATE INDEX idx_tickets_created_at_id
    ON tickets (created_at DESC, id DESC);

--------------------------------------------------------------------------------------------------
-- 2. idx_tickets_assigned_to_created_at  (assigned_to, created_at DESC, id DESC)
--------------------------------------------------------------------------------------------------
-- Supports:  "an agent's own queue" - WHERE assigned_to = ? ORDER BY created_at DESC
-- Measured:  414 buffers -> 11 buffers (Bitmap Index Scan on this index).
-- Secondary benefit: assigned_to is a foreign key (V11) and PostgreSQL does not index referencing
--            columns automatically, so this also makes the RESTRICT check on user deletion cheap.
-- Write cost: maintained on assignment changes, which are far rarer than list reads.
CREATE INDEX idx_tickets_assigned_to_created_at
    ON tickets (assigned_to, created_at DESC, id DESC);

--------------------------------------------------------------------------------------------------
-- 3. idx_tickets_raised_by_created_at  (raised_by, created_at DESC, id DESC)
--------------------------------------------------------------------------------------------------
-- Supports:  "tickets I reported" - WHERE raised_by = ? ORDER BY created_at DESC
-- Measured:  414 buffers -> 11 buffers (Bitmap Index Scan on this index).
-- Secondary benefit: same foreign-key argument as above for raised_by.
-- Write cost: raised_by never changes after creation, so maintenance is insert-only.
CREATE INDEX idx_tickets_raised_by_created_at
    ON tickets (raised_by, created_at DESC, id DESC);

--------------------------------------------------------------------------------------------------
-- 4. idx_tickets_status_created_at  (status, created_at DESC, id DESC)
--------------------------------------------------------------------------------------------------
-- Supports:  the COUNT behind a status-filtered page - SELECT count(*) ... WHERE status = ?
-- Measured:  419 buffers (Bitmap Heap Scan) -> 15 buffers (Index Only Scan on this index).
-- Honest note: the planner does NOT use this index for the status-filtered *page* query at 20,000
--            rows - it prefers idx_tickets_created_at_id (6 buffers) because 'OPEN' matches ~12% of
--            rows and only 20 are needed. This index is kept for the count, and because on a much
--            larger table with a more selective status the planner can switch to it. The decision is
--            left to the optimizer, which is why both indexes exist.
-- Write cost: status changes on every transition, so this index is the most write-active of the
--            four. Justified by turning a full-table count into an index-only scan.
CREATE INDEX idx_tickets_status_created_at
    ON tickets (status, created_at DESC, id DESC);

--------------------------------------------------------------------------------------------------
-- REJECTED CANDIDATE, recorded so it is not re-proposed
--------------------------------------------------------------------------------------------------
-- idx_tickets_priority_created_at (priority, created_at DESC, id DESC) was created and measured.
-- pg_stat_user_indexes reported idx_scan = 0: with only four distinct priority values over 20,000
-- rows the planner always preferred idx_tickets_created_at_id (4 buffers). It was 856 kB of index to
-- maintain on every write for no measured read benefit, so it is deliberately NOT created.
--
-- Also deliberately not created:
--   * ticket_number - already covered by the tickets_ticket_number_key unique index. Verified:
--     lookup by ticket number is an Index Scan at 3 buffers. Sequence-based generation (V10) and
--     lookup indexing are separate concerns, and the lookup side was already correct.
--   * updated_at - sortable but not a default, and no query filters on it yet.
--   * application_id, module_name, issue_type, business_impact, date ranges - no API filter uses
--     them, so an index would be pure write overhead.
--   * ticket_comments.ticket_id / ticket_attachments.ticket_id - those features are not implemented,
--     so there is no query to support yet.
--
-- NOTE ON PRODUCTION DEPLOYMENT
-- These statements take a brief ACCESS EXCLUSIVE lock on tickets, which is acceptable here because
-- the table is small in every current environment. Against a large live table, use
-- CREATE INDEX CONCURRENTLY in a migration configured as non-transactional instead.
