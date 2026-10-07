# Database Migrations

Schema changes are made **only** through Flyway migrations in
`src/main/resources/db/migration`. `spring.jpa.hibernate.ddl-auto=validate` is deliberate: Hibernate
never creates or alters anything, and the application refuses to start if an entity mapping
disagrees with the migrated schema.

## Rules

1. **Never edit a migration that has been applied anywhere.** Flyway stores a CRC32 checksum of each
   file. Changing one byte makes every database that already ran it fail validation at startup with
   `Migration checksum mismatch`.
2. **Never delete a migration file.** The row stays in `flyway_schema_history` forever; losing the
   file creates the exact failure documented below.
3. **Never renumber** existing migrations.
4. **Never hand-apply DDL** to a database. If it is not in a migration, it does not exist.
5. New changes go in a **new, higher-numbered** migration.
6. Commit the migration **in the same commit** as the code that depends on it.
7. Prefer additive, backward-compatible changes so a rollback does not require a down-migration.
8. In production, create indexes with `CONCURRENTLY` in a migration marked non-transactional.

Rule 1 and rule 2 are not style preferences. The next section is what happens when they are broken.

## Incident: the lost V8 migration

### What happened

`V8__add_ticket_history_tracking_index.sql` was applied to the development database on
**2026-09-29** (`flyway_schema_history`: version 8, checksum **1344533264**, execution time 231 ms).
The file was then deleted and **never committed to any branch** - `git log --all` across `main`,
`feature/making-flow` and `feature/service-flow-1` shows it was never added.

The result was a silent divergence:

| | Development database | A fresh database |
|---|---|---|
| Last migration | V8 | V7 |
| `idx_ticket_history_tracking_ticket_changed_at` | present | **absent** |

Nothing in the build detected this. It was found during the 2026-10-03 audit by comparing
`flyway_schema_history` against the files on disk.

### Why it was worse than a missing index

Flyway's default `ignoreMigrationPatterns` ignores *future* migrations - applied versions higher than
anything available locally. With only V1-V7 on disk, V8 counted as "future" and was silently
tolerated, so the application started normally and the problem stayed invisible.

That tolerance disappears the moment any migration above V8 exists. Adding a `V9` immediately
produced:

```
Validate failed: Migrations have failed validation
Detected applied migration not resolved locally: 8.
```

**So the lost migration was blocking all future schema work**, not merely leaving an index behind.
Phase 2 (foreign keys, indexes) could not have added a single migration until it was resolved.

### Why the original file could not simply be restored

The content is unrecoverable. The index definition survives in the schema, but Flyway's checksum is
computed from the **file text**, and the original text is gone.

A brute-force reconstruction was attempted: Flyway's algorithm was reimplemented and calibrated
against V7 (computed `-353444730`, matching the recorded value exactly), then ~258,000 plausible
formattings of the `CREATE INDEX` statement were tested against checksum 1344533264. **No match.**
Since the checksum depends on exact whitespace, keyword casing and any comments the author wrote,
the search space is effectively unbounded, and forcing a match by padding bytes would be fabrication
rather than recovery.

Writing a V8 with different text was verified to break an existing database outright:

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 8
```

The application does not start at all in that state.

### What was done

`V8__add_ticket_history_tracking_index.sql` was **reconstructed from its observed effect** rather
than its original text. The DDL is taken verbatim from the surviving index definition in the
development database, and the file documents its own provenance. It uses `IF NOT EXISTS` so it is
idempotent.

Verified outcomes:

| Scenario | Result |
|---|---|
| Fresh database, `flyway migrate` | V1-V8 apply; `Successfully validated 8 migrations` |
| Fresh database, index present | Asserted by `FlywayMigrationTest.ticketHistoryTrackingIndexIsPresent` |
| Dev-like database (original V8 applied), `flyway validate` | **Fails:** `Migration checksum mismatch for migration version 8` |
| Same database after `flyway repair`, `flyway validate` | `Successfully validated 9 migrations` |
| After repair | V8 history row **still present**, `installed_on` still 2026-09-29, checksum realigned |

Every test above ran against throwaway databases. **The real development database was not
modified** - it still records checksum 1344533264.

## Action required on existing databases

Any database that applied the original V8 - currently just the local development database - needs a
**one-time** reconciliation. It will not start until this is done. Two options:

### Option A - recreate the database (recommended here)

The development database is **empty** (0 users, 0 tickets), so there is nothing to lose and no
history to manipulate:

```bash
psql -h localhost -U postgres -c "DROP DATABASE \"ITSoftwareSupport\";"
psql -h localhost -U postgres -c "CREATE DATABASE \"ITSoftwareSupport\";"
```

The application recreates the schema from V1-V8 on next start.

### Option B - repair the schema history

Use this if a database has data worth keeping. `repair` **updates the recorded checksum in place**;
it does not delete the history row:

```bash
./mvnw org.flywaydb:flyway-maven-plugin:11.14.1:repair -Dflyway.url=jdbc:postgresql://localhost:5432/ITSoftwareSupport -Dflyway.user=postgres -Dflyway.password=postgres -Dflyway.schemas=it_software_support_db -Dflyway.defaultSchema=it_software_support_db -Dflyway.locations=filesystem:src/main/resources/db/migration
```

Then confirm:

```bash
./mvnw org.flywaydb:flyway-maven-plugin:11.14.1:validate -Dflyway.url=jdbc:postgresql://localhost:5432/ITSoftwareSupport -Dflyway.user=postgres -Dflyway.password=postgres -Dflyway.schemas=it_software_support_db -Dflyway.defaultSchema=it_software_support_db -Dflyway.locations=filesystem:src/main/resources/db/migration
```

Pass credentials via environment variables or your shell history settings rather than committing
them anywhere.

> The Flyway Maven plugin is invoked by fully-qualified coordinates on purpose - it is **not** a
> declared plugin in `pom.xml`, so the build gains no new dependency for a one-off operation.

## How this is prevented from recurring

`migration/FlywayMigrationTest` runs on every build against an empty container and asserts:

- no migration is recorded as failed;
- `flyway_schema_history` contains **exactly** the migration scripts present on disk - a lost or
  unexpected migration fails the build immediately;
- every expected table exists;
- the V8 index specifically exists, since that is the one that went missing;
- Hibernate's `validate` agrees with the migrated schema (implied by the context starting).

The second assertion is the one that would have caught this on 2026-09-29.

## Current migrations

| Version | Script | Purpose |
|---|---|---|
| V1 | `create_users_table` | `users` |
| V2 | `create_applications_table` | `applications` |
| V3 | `create_tickets_table` | `tickets` |
| V4 | `create_ticket_comments_table` | `ticket_comments` |
| V5 | `create_ticket_attachments_table` | `ticket_attachments` |
| V6 | `create_ticket_history_table` | `ticket_history` - **orphaned**, see below |
| V7 | `create_ticket_history_tracking_table` | `ticket_history_tracking` |
| V8 | `add_ticket_history_tracking_index` | `(ticket_id, changed_at DESC)` index - reconstructed |
| V9 | `add_ticket_optimistic_locking` | `tickets.version` for optimistic locking (audit P1-4) |
| V10 | `create_ticket_number_sequence` | `ticket_number_seq`, replacing clock-based numbering (audit P0-5) |
| V11 | `add_foreign_keys` | 8 foreign keys, all `ON DELETE RESTRICT` (audit P0-8) |
| V12 | `add_ticket_list_indexes` | 4 measured ticket-list indexes (audit P1-3); rationale in docs/PERFORMANCE.md |
| V13 | `add_user_credentials` | Nullable `users.password_hash` for authentication (audit P0-4); see below |
| V14 | `add_user_list_index` | One measured index for the paged user directory (audit P2-1); see below |
| V15 | `create_refresh_tokens_and_token_version` | `refresh_tokens` table plus `users.token_version`, for session lifecycle and access-token revocation; see below |

## V13 — user credentials

**Purpose.** Phase 4 replaced the transitional `X-User-Id` identity header with real authentication,
which needs somewhere to keep a password verifier. Until V13 the `users` table had no credential column
at all — the application had no authentication of any kind.

**What it adds.** One column, plus a comment:

```sql
ALTER TABLE users ADD COLUMN password_hash VARCHAR(255);
```

- Stores a **BCrypt hash** produced by Spring Security's `PasswordEncoder` — never a plaintext password
  and never a reversibly encrypted one.
- `VARCHAR(255)` rather than BCrypt's 60 characters, deliberately: Spring Security's delegating encoder
  prefixes hashes with `{id}`, so the algorithm can be upgraded later without another migration.
- **No index.** Authentication looks users up by `email`, which already has a unique index from V1
  (`users_email_key`).

**Why it is nullable — a security property, not laziness.** Every user that existed before Phase 4 has
a `NULL` hash and therefore **cannot authenticate**. That is the correct fail-closed outcome: switching
authentication on must not hand every pre-existing account a usable login. A password is set
explicitly, either by an administrator through `POST /api/users` or by the one-time bootstrap
administrator described in `docs/SECURITY.md` §5.

The login flow treats a `NULL` hash as "no password set" and fails authentication with the *same*
generic 401 as a wrong password, so the response reveals nothing about the account's state.

Making the column `NOT NULL` would have required inventing a password for every existing row — either a
shared default (a backdoor) or an unusable placeholder (the same fail-closed behaviour, with a
misleading schema). Nullable states the truth: some accounts have no credential.

**Migration ordering.** V13 is purely additive and touches nothing earlier. It is the last migration in
the chain and depends only on `users` existing (V1). Adding a column to a small table takes a brief
`ACCESS EXCLUSIVE` lock; on PostgreSQL a nullable column with no default is a metadata-only change, so
no table rewrite occurs.

**Verification.**

| Check | Result |
|---|---|
| Fresh database | V1–V13 apply in order; Flyway validates |
| Recorded scripts match files on disk | Asserted by `FlywayMigrationTest.schemaHistoryMatchesMigrationsOnDisk`, which lists every script explicitly |
| Hibernate `ddl-auto=validate` | Context starts, so `User.passwordHash` agrees with the migrated column |
| Existing database | Additive and nullable, so it applies without data migration or backfill |

## V14 — user directory index

**Purpose.** Phase 6 paginated `GET /api/users` (audit P2-1). The default page orders by
`full_name ASC, id ASC`, and without an index PostgreSQL answered every such page by scanning the whole
table and top-N heapsorting it.

**What it adds.** One index:

```sql
CREATE INDEX idx_users_full_name_id ON users (full_name, id);
```

**Why, with numbers.** Measured with `EXPLAIN (ANALYZE, BUFFERS)` against 50,000 users on
PostgreSQL 18, with realistic name duplication (only 200 distinct names across 50,000 rows):

| | Plan | Buffers | Time |
|---|---|--:|--:|
| Before | Seq Scan + top-N heapsort | 1,105 | 55.6 ms |
| After | Index Scan | 23 | 0.16 ms |

48x fewer buffers, and the sort disappears because the index already holds the order. Index size is
2 MB against an 8.8 MB table (~23%), maintained only when a user is created or renamed.

**Why `(full_name, id)` and not `(full_name)`.** `id` is the tiebreaker the API always appends. With
250 users sharing each name at this distribution, ordering by `full_name` alone is non-deterministic
across ties, and offset paging can then skip and repeat rows between pages. The index has to match the
`ORDER BY` for the sort to be eliminated.

**What was deliberately NOT added.** The migration file records each rejection inline so it is not
re-proposed:

- `applications (app_name, id)` — **measured and rejected.** 500 reference rows occupy 5 pages, so the
  page already costs 5 buffers and 0.35 ms. There is no room for an index to help.
- `users.email`, `users.employee_code` — the V1 `UNIQUE` constraints already index them. Verified:
  `ORDER BY email, id` is an Index Scan feeding an Incremental Sort at 9 buffers.
- `users.role`, `users.active` — whitelisted sort keys but 7 and 2 distinct values, and never the
  default.
- Anything for the count query — `SELECT count(u.id) FROM users` is already an Index Only Scan on
  `users_pkey` (139 buffers, zero heap fetches).

**Migration ordering.** Purely additive, depends only on `users` existing (V1), and touches no earlier
migration. It takes a brief `ACCESS EXCLUSIVE` lock on `users`; against a large live table use
`CREATE INDEX CONCURRENTLY` in a non-transactional migration instead. Same caveat as V12.

**Verification.**

| Check | Result |
|---|---|
| Fresh database | V1–V14 apply in order; Flyway validates |
| Index present, rejected one absent | Asserted by `FlywayMigrationTest.userListIndexExists` |
| No duplicate index on unique columns | Asserted by `FlywayMigrationTest.userUniqueColumnsAreNotDuplicated` |
| Existing database | Additive; no data migration or backfill |

## V15 - refresh tokens and the revocation counter

**Why.** Phases 4 and 5 both recorded the same two open limitations: there was no refresh flow, so a
client had to re-submit the password every 30 minutes; and an access token could not be revoked, so
deactivating a user or changing a password left every already-issued token valid until it expired. Both
fixes need persistent state, which is what this migration adds.

**`users.token_version INTEGER NOT NULL DEFAULT 0`.** A monotonic counter. Every access token carries
the value current at issue time in a `tv` claim; a mismatch is refused. Incrementing it therefore ends
every outstanding token for that user at once, with no per-token denylist to grow without bound.

A counter rather than a `revoked_before` timestamp deliberately: a counter needs no clock agreement
between the issuer and the verifier, and cannot be defeated by skew or by two revocations landing in the
same clock tick.

`NOT NULL DEFAULT 0` so every existing row is valid immediately and no backfill is needed.

**`refresh_tokens`.** One row per refresh-token credential.

| Column | Note |
|---|---|
| `token_hash VARCHAR(64) NOT NULL UNIQUE` | Hex SHA-256 of the token handed to the client. The plaintext is never stored. SHA-256 rather than BCrypt because the token is 256 bits of CSPRNG output, not a human-chosen password - and because BCrypt salts, so a lookup would have to verify against every row. The unique index is the refresh hot path |
| `user_id BIGINT NOT NULL` | FK to `users`, **`ON DELETE CASCADE`** - see below |
| `family_id VARCHAR(36) NOT NULL` | Groups one rotation chain, so a replayed token revokes every descendant. A string rather than `UUID`, to keep the Hibernate mapping within types `ddl-auto=validate` already checks elsewhere in this schema |
| `issued_at`, `expires_at`, `revoked_at` | `TIMESTAMP`, written in UTC |
| `revoked_reason VARCHAR(40)` | Which `RevocationReason` ended it, so a detected replay is distinguishable from a routine rotation in an investigation |

**The one `CASCADE` in the schema, and why it is not a contradiction of V11.** V11 chose `RESTRICT`
everywhere, on the grounds that deleting a user must not silently erase their tickets or their audit
trail. The opposite is true here: session credentials are derived authentication state, not business
history, and a deleted user's live sessions must not outlive the account. `RESTRICT` would also make
deleting a user impossible while any token row remained. `FlywayMigrationTest.foreignKeysExist` asserts
this one constraint cascades and that every other still restricts, so the exception stays a decision
rather than a drift.

**Indexes.** Three, each for a statement that exists:

- the `UNIQUE` constraint on `token_hash` serves the refresh lookup; no second index is added for it
- `idx_refresh_tokens_user_live` - partial, `WHERE revoked_at IS NULL` - serves "revoke every live
  session for this user" and the session count on `GET /api/auth/me`. Partial so it does not grow with
  historical, already-revoked rows
- `idx_refresh_tokens_family` - serves family-wide revocation
- `idx_refresh_tokens_expires_at` - serves the scheduled purge

**`TIMESTAMP`, not `TIMESTAMPTZ`.** This matches every other timestamp column in the schema
(`tickets.created_at`, `ticket_history_tracking.changed_at`), and the application writes all of them in
UTC through an injected `Clock` fixed to UTC. A zone-less column is unambiguous only if everything
writing to it agrees on one; introducing a second timestamp convention for a single table would be a
worse trade than keeping the one the whole schema shares.

**Migration ordering.** Purely additive. The new column depends only on `users` existing (V1), and the
new table on the same. Nothing earlier is touched. `ALTER TABLE ... ADD COLUMN` with a non-volatile
default does not rewrite the table on PostgreSQL 11+, so it is fast even on a large `users` table.

**Verification.**

| Check | Result |
|---|---|
| Fresh database | V1-V15 apply in order; Flyway validates |
| Script list matches disk | Asserted by `FlywayMigrationTest.schemaHistoryMatchesMigrationsOnDisk` |
| `refresh_tokens` exists | Asserted by `FlywayMigrationTest.expectedTablesExist` |
| FK present, cascade is the only one | Asserted by `FlywayMigrationTest.foreignKeysExist` |
| Entity matches schema | `ddl-auto=validate` passes at startup in every integration test |
| Existing database | Additive; no data migration or backfill. Tokens issued before V15 carry no `tv` claim and are refused, costing one re-login |

### Known schema debt

- **V6 `ticket_history` is an orphan table.** Its entity was deleted in commit `799ec14` and replaced
  by `ticket_history_tracking` (V7). The table is created in every environment and used by nothing.
  Dropping it needs a migration and is deferred - see audit finding P2-2.
- **Foreign keys were added in V11** (audit P0-8) and **ticket-list indexes in V12** (audit P1-3).
  Each V12 index is justified by an EXPLAIN measurement recorded in docs/PERFORMANCE.md; a fifth
  candidate on `priority` was built, measured at zero index scans, and deliberately not shipped.
- `users.email` length was realigned to 100 on the entity to match V1 (audit P1-8), and `@Size`
  constraints now mirror the column widths so over-length input is a 400 rather than a database error.
