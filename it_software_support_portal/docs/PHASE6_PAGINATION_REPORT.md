# Phase 6 — Pagination for `/api/users` and `/api/applications`

**Scope:** bound the last three unbounded collection endpoints. No new dependency, no new
infrastructure, no change to authentication, authorization, JWT handling or ticket behaviour.

Audit finding closed: **P2-1**, the last open item of section 12 ("Remaining bottlenecks") in
docs/PERFORMANCE.md.

| Endpoint | Before | After |
|---|---|---|
| `GET /api/users` | every row | page of 20, cap 100 |
| `GET /api/applications` | every row | page of 20, cap 100 |
| `GET /api/applications/active` | every active row | page of 20, cap 100, filter preserved |

---

## A. What the defect actually was

The ticket list had two defects. These endpoints had only one.

| Defect | Ticket list (Phase 3) | Users / applications |
|---|---|---|
| Unbounded result set | yes | **yes** |
| N+1 on associations | yes, up to 2N+1 statements | **no** |

Neither `User` nor `Application` declares a single `@ManyToOne`, `@OneToMany` or `@ManyToMany`. That
was verified by grep before any design decision, not assumed:

```
grep -cE "@ManyToOne|@OneToMany|@ManyToMany" entity/User.java      -> 0
grep -cE "@ManyToOne|@OneToMany|@ManyToMany" entity/Application.java -> 0
```

So a page was already two statements and needed no fetch-strategy work. The defect was purely response
size — the audit measured **787 KB for 5,103 users**, growing linearly with the table and with no
ceiling. Stating this explicitly matters because it is the reason this phase is small: the expensive
half of the Phase 3 work did not apply.

## B. Contract — deliberately identical to tickets

The existing ticket contract was reused exactly, so a client that already pages tickets needs no new
machinery:

- `page` (default 0), `size` (default 20, **hard cap 100**), `sort` (`property` or `property,asc|desc`).
- Body stays a **plain JSON array**. Metadata goes in headers: `X-Total-Count`, `X-Total-Pages`,
  `X-Page-Number`, `X-Page-Size`, `X-Has-Next`.
- Sortable properties are whitelisted; anything else is a **400**, never a silent fallback.
- `id` is always appended as a tiebreaker.
- Out-of-range page returns an empty array with a correct `X-Total-Count`, not a 404.

| Endpoint | Default sort | Sortable |
|---|---|---|
| `/api/users` | `fullName,asc` | `fullName`, `email`, `employeeCode`, `role`, `active`, `id` |
| `/api/applications` | `appName,asc` | `appName`, `moduleName`, `active`, `id` |

Names were chosen because a directory and a catalogue are browsed alphabetically, where a ticket queue
is browsed newest-first.

**Why the array body was kept.** Wrapping the response in a `{content, page}` envelope would have been
a breaking change to three endpoints for no functional gain. The headers already carry everything a
client needs, and CORS `exposedHeaders` was configured for them in Phase 4.

**Why the cap matters.** Without it the endpoints would still be unbounded in practice, because a client
could ask for `size=1000000`. `UserAndApplicationPaginationTest.usersPageSizeIsCapped` asserts the cap
by requesting exactly that.

## C. The `id` tiebreaker is not decoration

Unlike `ticketNumber`, `full_name` is not unique. The test fixture is built to make this real: 50,000
users share only 200 distinct names, so roughly 250 users carry each name. Ordering by `full_name`
alone is non-deterministic across ties, and offset paging then **skips and repeats rows** between
pages. `usersPagingIsStable` pages through 45 users in pages of 10 and asserts 45 distinct ids —
it fails if the tiebreaker is removed.

## D. Code shape

`TicketPageRequests` already held the parse-and-validate mechanics. Rather than copy it twice, the
shared parts moved to `PageRequests` and the three callers became whitelist-plus-default-sort
declarations:

| File | Lines | Role |
|---|--:|---|
| `common/web/PageRequests.java` | 93 | shared: bounds, cap, sort parsing, whitelist, tiebreaker, headers |
| `common/web/TicketPageRequests.java` | 41 | ticket whitelist and default sort |
| `common/web/UserPageRequests.java` | 32 | user whitelist and default sort |
| `common/web/ApplicationPageRequests.java` | 28 | application whitelist and default sort |
| `repository/projection/UserRow.java` | 28 | 8-field read projection, no `password_hash` |
| `db/migration/V14__add_user_list_index.sql` | 59 | one index, with the rejection reasoning inline |

`TicketPageRequests` kept its **public API unchanged**, so `TicketController` and its 18 pagination
tests needed no edits. Those 18 tests passing unmodified is the evidence that the extraction preserved
behaviour; they were not adjusted to fit the refactor.

## E. A projection for users, none for applications

This asymmetry is deliberate and documented at both sites.

**`UserRow` exists for a security reason, not a performance one.** `User` carries `password_hash`.
Loading the entity to render a directory listing pulls every hash on the page into application memory,
where a logger, a debugger, a serialization change or a future DTO field could expose it. The cheapest
way to protect a secret is not to fetch it. The projection selects eight columns and the hash is not
among them.

**`Application` gets no projection.** Its four columns are exactly the four its response carries, so
loading the entity already reads nothing spare. A projection there would be a type for no measurable
benefit.

`UserListBaselineTest` asserts both directions against the SQL that reaches the driver:

- the listing statement does not contain `password_hash`;
- the **login** path still does — a projection applied where verification needs the hash would be
  secure and useless.

## F. Measured: the index decision

`EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` against 50,000 users and 500 applications, PostgreSQL 18.

| Query | Before V14 | After V14 |
|---|--:|--:|
| Users page 0 | Seq Scan + top-N heapsort, **1,105 buffers, 55.6 ms** | Index Scan, **23 buffers, 0.16 ms** |
| Users page 100 (`OFFSET 2000`) | 1,145 buffers, **113 ms** | 2,032 buffers, **2.2 ms** |
| Users exact count | Index Only Scan on `users_pkey`, 139 buffers | unchanged |
| Users `ORDER BY email` | Index Scan + Incremental Sort, 9 buffers | unchanged |
| Applications page 0 | Seq Scan + heapsort, **5 buffers, 0.35 ms** | **no index created** |
| Applications active page 0 | Seq Scan + heapsort, **5 buffers, 0.34 ms** | **no index created** |

**One index created:** `users (full_name, id)` — 48x fewer buffers on the first page, and the sort
disappears entirely. 2 MB against an 8.8 MB table.

**One index rejected after measuring it:** `applications (app_name, id)`. A 500-row reference table
occupies 5 pages, so the page already costs 5 buffers. There is nothing for an index to improve, and it
would be write overhead plus a second structure to keep correct. Recorded in V14 so it is not
re-proposed.

**Nothing added for** `email` or `employee_code` (the V1 `UNIQUE` constraints already index them, and
sorting by `email` was verified to use `users_email_key`), for `role` or `active` (7 and 2 distinct
values), or for the count query (already an index-only scan with zero heap fetches).

### Honest note: deep offsets cost more buffers, not fewer

At `OFFSET 2000` the index makes execution **51x faster** but reads **more** buffers (1,145 → 2,032),
because an ordered walk of 2,020 rows replaces one sort of 50,000. Offset pagination is inherently
O(offset) with or without an index. Keyset pagination is the real fix for deep iteration; it is a
contract change and was **not** attempted in this phase. Recorded in docs/PERFORMANCE.md §6 and §15
rather than buried.

## G. Tests

| Class | Tests | Covers |
|---|--:|---|
| `UserAndApplicationPaginationTest` | 14 | defaults, array body preserved, page/size, size cap, stable paging, out-of-range page, sorting, whitelist rejection, headers, active filter preserved, authorization unchanged |
| `UserListBaselineTest` | 4 | statement count constant from 50 to 500 rows for both endpoints; listing SQL never reads `password_hash`; login still does |
| `FlywayMigrationTest` | +2 | V14's index present, the rejected one absent, no duplicate index on unique columns |
| `UserApiCharacterizationTest` | 1 rewritten | `userListIsUnbounded` → `userListIsPaginated` |

Two assertions are worth calling out as deliberate:

- `usersSorting` asserts that `?sort=passwordHash` is a **400**. The whitelist is not decoration —
  without it a caller could name any entity property as an order key, and `passwordHash` is one.
- `authorizationStillApplies` asserts unauthenticated is still 401, an `EMPLOYEE` still gets 403 on
  `/api/users`, and the catalogue is still readable. Adding paging must not quietly turn a permission
  check into a page-size check.

### New test infrastructure: `CapturingStatementInspector`

Asserting that a query does not *read* a column is not something a statement count can show. A
JDBC-level `StatementInspector` records the SQL Hibernate sends, so — like the Phase 3 statement counts
— the assertion cannot be satisfied by changing logging configuration. Recording is off until a test
calls `capturingSql(...)`, and the buffer is cleared on start, so the rest of the suite pays nothing
and cannot accumulate statements across hundreds of tests.

## H. Verification

**Docker is not available on this machine**, so Testcontainers execution is not possible here. Per the
standing agreement, the suite was run against a throwaway local PostgreSQL 18 database by temporarily
pointing `AbstractIntegrationTest.datasourceProperties` at it and deactivating the Docker condition.
**Both shims have been reverted and verified removed** (`diff` against a pre-shim copy reports the file
identical; `git diff` on `ConcurrencyRegressionTest` is empty; a grep for shim markers and local-database
references across `src/` finds none). The throwaway databases were dropped.

First full run against local PostgreSQL:

```
Tests run: 233, Failures: 3, Errors: 3, Skipped: 2
```

All six problems were accounted for individually — two real, four shim artifacts:

| Problem | Verdict |
|---|---|
| `FlywayMigrationTest.schemaHistoryMatchesMigrationsOnDisk` | **real** — the inventory lists every script explicitly and V14 was new. Fixed by adding V14. |
| `UserListBaselineTest.userListingDoesNotSelectPasswordHash` | **real, and my test's fault.** It caught `tokenFor(...)` loading the `User` entity — harness SQL, not the endpoint's. Fixed by minting the token before opening the recording window. **No production defect:** the listing statement was clean in the captured SQL. |
| `ConcurrencyRegressionTest` ×3 | **shim artifact** — they open a second connection via `POSTGRES.getJdbcUrl()`. Re-run with that one connection pointed at the local database: **8/8 pass**, so Phase 2's optimistic locking is intact. Shim reverted. |
| `ItSoftwareSupportPortalApplicationTests.usesContainerDatabaseNotLocalhostDevDatabase` | **unverifiable under a shim by construction** — it exists to assert the container is in use. Not run; not claimed as passing. |

After both real fixes (which added 2 migration tests, hence 235):

```
Tests run: 235, Failures: 1, Errors: 3, Skipped: 2
```

— the remaining 4 being exactly the shim artifacts above. With the concurrency connection also pointed
at the local database, those 3 pass, leaving:

```
234 of 235 tests pass; 2 skipped by design (undecided business rules)
1 not run: usesContainerDatabaseNotLocalhostDevDatabase (asserts the container itself)
```

Default build on this machine, no Docker, shims reverted:

```
Tests run: 234, Failures: 0, Errors: 0, Skipped: 205
BUILD SUCCESS
```

**That green build means "not contradicted", not "verified"** — only 29 tests actually execute without
a container. The meaningful numbers are the local-PostgreSQL run above.

### Prior-phase guarantees re-checked

| Guarantee | Phase | Result |
|---|---|---|
| Ticket list is **2 SQL statements**, constant from 50 to 500 rows | 3 | `TicketListBaselineTest` 4/4 pass |
| 18 ticket pagination tests, **unmodified** | 3 | 18/18 pass — validates the `PageRequests` extraction |
| Optimistic locking rejects stale writes, maps to 409 | 2 | 8/8 pass (with the second connection shimmed) |
| Foreign keys, ticket identity, status integrity, error handling | 1–2 | 47/47 pass |
| Authentication, authorization, resource access control | 4 | 37/37 pass |
| Login rate limiting and security headers | 5 | 33/33 pass |
| Migration chain applies to an empty database | 1 | 13/13 pass, now V1–V14 |

## I. Documentation updated

- **docs/PERFORMANCE.md** — new §15 (the full Phase 6 measurement set); §12 item 1 marked fixed; title
  and preamble scoped to say which sections cover what.
- **docs/MIGRATIONS.md** — V14 added to the migration table, plus a V14 section with the numbers, the
  `(full_name, id)` reasoning, and every rejected candidate.
- **docs/TESTING.md** — new "Fixed in Phase 6" row; P2-1 removed from "Still outstanding"; the
  `CapturingStatementInspector` note including the `tokenFor` trap; Docker-free counts refreshed.
- **docs/SECURITY.md** — §11 item 7 marked done, with the two security-relevant details (sort whitelist,
  no-hash projection).

### Pre-existing documentation defects found and corrected

Found while updating, not introduced by this phase: docs/TESTING.md's "Performance baselines" section
still described `TicketListBaselineTest` as recording *before* numbers and referenced two test methods —
`fullScaleAuditBaseline` and `statementCountIsIndependentOfRowCount` — that **no longer exist** in
source; Phase 3 rewrote that class. It also described the `performance` package as holding "BEFORE
measurements". Both were rewritten to match the code. Verified by grep that neither method name appears
anywhere in `src/`.

## J. Known limitations

1. **Deep offset pages remain O(offset).** Section F. Keyset pagination is the fix and is a contract
   change.
2. **The exact count is now the larger half of a user page request** — 139 buffers against the page's
   23. An exact total requires reading every index entry. Spring Data already skips the count when a
   page cannot have a successor; if it becomes dominant, the options are `pg_class.reltuples` or a
   no-count `Slice`.
3. **No filtering or search on either endpoint.** This phase bounded them; it did not add
   `?role=`, `?active=` or name search. Those are query-capability features, and adding them without a
   stated requirement would be inventing an API. Audit P2-1's search half remains open (roadmap phase 9).
4. **The applications index decision is volume-dependent.** It is correct for a 500-row catalogue. V14
   says to re-measure if that table ever reaches tens of thousands of rows.
5. **No HTTP caching** on any endpoint — unchanged from Phase 3.

## K. Business decisions still pending

Unchanged by this phase, and still not decided by inference:

1. **Which ticket status transitions are legal** (P1-5) — blocks the workflow guard.
2. **Whether reassignment resets status** (P2-12) — `assignTicket` forces `ASSIGNED`.
3. **Department visibility for `MANAGER` / `HOD` / `DIRECTOR`** — `users.department_id` references no
   table, so there is nothing to scope by.
4. **Whether a requester may change status on their own ticket.**

Item 3 is the one this phase came closest to touching: a department-scoped user directory would be the
natural next step for `/api/users`, and it is deliberately not implemented because no rule establishes
who may see whom.

## L. Scope confirmation

**Changed:** three controller read methods, two services, two repositories, four pagination-request
classes, one projection, one migration, one test-support class, three test classes, four docs.

**Not changed:** authentication, authorization rules, permission definitions, JWT handling, rate
limiting, ticket behaviour, entity mappings, dependency list, every migration V1–V13.

**Not done, deliberately:** keyset pagination, filtering, search, response-envelope change, caching, and
any index not justified by a measurement.
