package com.forward.it_software_support_portal.migration;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards migration discipline.
 *
 * <p>These tests exist because a migration (the original V8) was applied to the development database
 * and then lost from source control, leaving development and fresh environments with different
 * schemas - and nothing in the build detected it. Running the full migration chain against an empty
 * container on every build is what makes that class of mistake visible.
 */
@DatabaseIntegrationTest
class FlywayMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("every migration on disk applies cleanly to an empty database")
    void allMigrationsApplySuccessfully() {
        List<String> failed = jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success = false", String.class);

        assertThat(failed).as("no migration may be recorded as failed").isEmpty();
    }

    @Test
    @DisplayName("schema history contains exactly the migrations present on disk")
    void schemaHistoryMatchesMigrationsOnDisk() {
        List<String> applied = jdbc.queryForList("""
                SELECT script FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """, String.class);

        assertThat(applied).containsExactly(
                "V1__create_users_table.sql",
                "V2__create_applications_table.sql",
                "V3__create_tickets_table.sql",
                "V4__create_ticket_comments_table.sql",
                "V5__create_ticket_attachments_table.sql",
                "V6__create_ticket_history_table.sql",
                "V7__create_ticket_history_tracking_table.sql",
                "V8__add_ticket_history_tracking_index.sql",
                "V9__add_ticket_optimistic_locking.sql",
                "V10__create_ticket_number_sequence.sql",
                "V11__add_foreign_keys.sql",
                "V12__add_ticket_list_indexes.sql",
                "V13__add_user_credentials.sql",
                "V14__add_user_list_index.sql");
    }

    @Test
    @DisplayName("all expected tables exist after migration")
    void expectedTablesExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).contains(
                "applications", "ticket_attachments", "ticket_comments",
                "ticket_history", "ticket_history_tracking", "tickets", "users");
    }

    @Test
    @DisplayName("the index restored by V8 is present on a fresh database")
    void ticketHistoryTrackingIndexIsPresent() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'ticket_history_tracking'
                """, String.class);

        assertThat(indexes)
                .as("""
                        V8 was lost from source control and had to be reconstructed. If this index is \
                        missing, a fresh environment has silently diverged from development again.""")
                .contains("idx_ticket_history_tracking_ticket_changed_at");
    }

    @Test
    @DisplayName("V9 adds a NOT NULL version column to tickets for optimistic locking")
    void ticketVersionColumnExists() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'tickets' AND column_name = 'version'
                """);

        assertThat(column).containsEntry("data_type", "bigint");
        assertThat(column).containsEntry("is_nullable", "NO");
        assertThat((String) column.get("column_default"))
                .as("a default lets the column be NOT NULL while backfilling existing rows")
                .contains("0");
    }

    @Test
    @DisplayName("V10 creates the ticket number sequence")
    void ticketNumberSequenceExists() {
        List<String> sequences = jdbc.queryForList("""
                SELECT sequence_name FROM information_schema.sequences
                WHERE sequence_schema = current_schema()
                """, String.class);

        assertThat(sequences).contains("ticket_number_seq");
    }

    @Test
    @DisplayName("V11 adds the expected foreign keys, all with RESTRICT delete behaviour")
    void foreignKeysExist() {
        List<Map<String, Object>> fks = jdbc.queryForList("""
                SELECT con.conname AS name, con.confdeltype AS on_delete
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = rel.relnamespace
                WHERE n.nspname = current_schema() AND con.contype = 'f'
                ORDER BY con.conname
                """);

        assertThat(fks).extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder(
                        "fk_tickets_raised_by",
                        "fk_tickets_assigned_to",
                        "fk_tickets_application",
                        "fk_ticket_history_tracking_ticket",
                        "fk_ticket_history_tracking_changed_by",
                        "fk_ticket_comments_ticket",
                        "fk_ticket_comments_commented_by",
                        "fk_ticket_attachments_ticket");

        // 'r' = RESTRICT. No constraint may cascade: deleting a user or ticket must never silently
        // erase tickets or audit history.
        assertThat(fks).allSatisfy(row ->
                assertThat(row.get("on_delete"))
                        .as("constraint %s must be ON DELETE RESTRICT, never CASCADE", row.get("name"))
                        .isEqualTo("r"));
    }

    @Test
    @DisplayName("the orphaned V6 ticket_history table is deliberately left unconstrained")
    void orphanTableIsNotConstrained() {
        List<String> fks = jdbc.queryForList("""
                SELECT con.conname FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = rel.relnamespace
                WHERE n.nspname = current_schema() AND con.contype = 'f' AND rel.relname = 'ticket_history'
                """, String.class);

        assertThat(fks)
                .as("""
                        ticket_history is an orphan (entity deleted in 799ec14, replaced by \
                        ticket_history_tracking) and is scheduled for removal - constraining a dead \
                        table would only make dropping it harder. See audit finding P2-2.""")
                .isEmpty();
    }

    @Test
    @DisplayName("V12 adds the four measured ticket-list indexes, and not the rejected one")
    void ticketListIndexesExist() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'tickets'
                """, String.class);

        assertThat(indexes).contains(
                "idx_tickets_created_at_id",
                "idx_tickets_assigned_to_created_at",
                "idx_tickets_raised_by_created_at",
                "idx_tickets_status_created_at");

        assertThat(indexes)
                .as("""
                        A priority index was built, measured with pg_stat_user_indexes and used zero \
                        times, so it was deliberately not created. See V12.""")
                .doesNotContain("idx_tickets_priority_created_at");
    }

    @Test
    @DisplayName("V14 adds the measured user-list index, and not the rejected applications one")
    void userListIndexExists() {
        List<String> userIndexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'users'
                """, String.class);

        assertThat(userIndexes)
                .as("""
                        Measured at 50,000 users: the default directory page went from a Seq Scan plus \
                        top-N heapsort (1,105 buffers) to an Index Scan (23 buffers). See V14.""")
                .contains("idx_users_full_name_id");

        List<String> applicationIndexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'applications'
                """, String.class);

        assertThat(applicationIndexes)
                .as("""
                        An app_name index was measured and rejected: 500 reference rows occupy 5 pages, \
                        so the page already costs 5 buffers and an index could not improve it. See V14.""")
                .doesNotContain("idx_applications_app_name_id");
    }

    @Test
    @DisplayName("user email and employee-code sorting is already covered - no duplicate index")
    void userUniqueColumnsAreNotDuplicated() {
        List<String> userIndexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'users'
                """, String.class);

        assertThat(userIndexes)
                .as("the UNIQUE constraints from V1 already index these columns")
                .contains("users_email_key", "users_employee_code_key");
        assertThat(userIndexes)
                .as("""
                        Both columns are whitelisted sort keys, and both were verified to sort through \
                        the existing unique index. V14 deliberately adds nothing for them.""")
                .doesNotContain("idx_users_email", "idx_users_employee_code");
    }

    @Test
    @DisplayName("ticket-number lookup is already covered - no duplicate index was added")
    void ticketNumberLookupIsNotDuplicated() {
        List<String> numberIndexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'tickets'
                  AND indexdef LIKE '%ticket_number%'
                """, String.class);

        assertThat(numberIndexes)
                .as("the unique constraint already provides the lookup index")
                .containsExactly("tickets_ticket_number_key");
    }

    @Test
    @DisplayName("Hibernate's schema validation agrees with the migrated schema")
    void hibernateValidatesAgainstMigratedSchema() {
        // spring.jpa.hibernate.ddl-auto=validate means the context would have failed to start if an
        // entity mapping disagreed with the migrated schema - including Ticket.version added in V9.
        assertThat(countRows("users")).isZero();
    }
}
