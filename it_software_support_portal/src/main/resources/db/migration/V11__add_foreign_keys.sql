-- Referential integrity for the core ticketing tables (audit finding P0-8).
--
-- Before this migration the schema had ZERO foreign keys: raised_by, assigned_to, application_id,
-- ticket_id, commented_by and changed_by were all bare BIGINTs. Nothing prevented a ticket pointing
-- at a deleted user or a nonexistent application, and audit rows could reference ghosts.
--
-- ORPHAN PRE-CHECK. This migration fails loudly (and rolls back) if orphan rows exist, rather than
-- deleting anything. Run this before applying it to a database with data:
--
--   SELECT 'tickets.raised_by'      AS ref, count(*) FROM tickets t
--     LEFT JOIN users u ON u.id = t.raised_by WHERE u.id IS NULL
--   UNION ALL SELECT 'tickets.assigned_to', count(*) FROM tickets t
--     LEFT JOIN users u ON u.id = t.assigned_to WHERE t.assigned_to IS NOT NULL AND u.id IS NULL
--   UNION ALL SELECT 'tickets.application_id', count(*) FROM tickets t
--     LEFT JOIN applications a ON a.id = t.application_id WHERE a.id IS NULL
--   UNION ALL SELECT 'ticket_history_tracking.ticket_id', count(*) FROM ticket_history_tracking h
--     LEFT JOIN tickets t ON t.id = h.ticket_id WHERE t.id IS NULL
--   UNION ALL SELECT 'ticket_history_tracking.changed_by', count(*) FROM ticket_history_tracking h
--     LEFT JOIN users u ON u.id = h.changed_by WHERE u.id IS NULL
--   UNION ALL SELECT 'ticket_comments.ticket_id', count(*) FROM ticket_comments c
--     LEFT JOIN tickets t ON t.id = c.ticket_id WHERE t.id IS NULL
--   UNION ALL SELECT 'ticket_comments.commented_by', count(*) FROM ticket_comments c
--     LEFT JOIN users u ON u.id = c.commented_by WHERE u.id IS NULL
--   UNION ALL SELECT 'ticket_attachments.ticket_id', count(*) FROM ticket_attachments a
--     LEFT JOIN tickets t ON t.id = a.ticket_id WHERE t.id IS NULL;
--
-- Orphans found at the time of this migration: NONE (every environment was empty).
--
-- DELETE BEHAVIOR: every constraint uses ON DELETE RESTRICT. No CASCADE is used anywhere, because
-- no business requirement justifies one:
--   * Deleting a user must not silently erase or orphan their tickets or their audit entries.
--   * Deleting a ticket must not destroy its audit trail - that is the opposite of auditability.
--   * There is no delete operation for tickets or users in the API today, so RESTRICT costs nothing
--     and prevents a future delete endpoint from quietly shredding history.
-- Deliberate removal of a user or ticket therefore becomes an explicit, deliberate data operation
-- rather than an accidental cascade.
--
-- NULLABILITY matches the existing column definitions and is not changed here:
--   * tickets.raised_by, tickets.application_id   NOT NULL (every ticket has a raiser and an app)
--   * tickets.assigned_to                         NULL     (a new ticket is legitimately unassigned)
--   * ticket_history_tracking.ticket_id/changed_by NOT NULL
--   * ticket_comments.ticket_id/commented_by      NOT NULL
--   * ticket_attachments.ticket_id                NOT NULL
--
-- NOT CONSTRAINED, deliberately:
--   * ticket_history - the V6 table is an orphan (its entity was deleted in commit 799ec14 and
--     replaced by ticket_history_tracking). It is used by nothing and is scheduled for removal
--     (audit P2-2); adding constraints to a dead table would only make dropping it harder.
--   * users.department_id, users.designation_id - no departments or designations table exists yet
--     (audit P2-13). These become foreign keys when those tables are introduced.
--
-- No indexes are created here. PostgreSQL does not require an index on the referencing column for a
-- foreign key, and index work is deliberately deferred to the performance phase so the before/after
-- measurement stays clean.

ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_raised_by
        FOREIGN KEY (raised_by) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tickets_assigned_to
        FOREIGN KEY (assigned_to) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tickets_application
        FOREIGN KEY (application_id) REFERENCES applications (id) ON DELETE RESTRICT;

ALTER TABLE ticket_history_tracking
    ADD CONSTRAINT fk_ticket_history_tracking_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ticket_history_tracking_changed_by
        FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE ticket_comments
    ADD CONSTRAINT fk_ticket_comments_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ticket_comments_commented_by
        FOREIGN KEY (commented_by) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE ticket_attachments
    ADD CONSTRAINT fk_ticket_attachments_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE RESTRICT;
