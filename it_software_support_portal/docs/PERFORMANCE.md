# Performance and Scalability (Phase 3, extended in Phase 6)

All figures below were measured on one machine (local PostgreSQL 18, JDK 25) using
`TicketListBenchmark`, and for section 15 with `EXPLAIN (ANALYZE, BUFFERS)` against a throwaway
database. They are meaningful as **before/after comparisons on the same environment**, not as absolute
capacity numbers. See "What was not measured" at the end.

Sections 1-14 cover the ticket list (Phase 3). Section 15 covers the user directory and application
catalogue (Phase 6).

SQL statement counts come from Hibernate's own `Statistics`, not from parsing logs, so they cannot be
improved by changing logging configuration.

---

## 1. What was wrong

`GET /api/tickets` called `findAll()` and mapped the resulting entities. Two independent problems:

1. **Unbounded result set.** Every row in the table was returned in one response.
2. **N+1 loading.** `Ticket` has three `@ManyToOne` associations, all `EAGER`. Spring Data's
   `findAll()` issues `select t from Ticket t`, and Hibernate resolves eager to-one associations in
   HQL results with *follow-up selects* rather than a join. The entity loader used by `findById`
   does join, which is why single-ticket retrieval always cost exactly one statement while the list
   did not.

The second point matters more precisely than "2N+1": the extra queries scale with the number of
**distinct** referenced users and applications in the result, because the persistence context dedupes
repeated references. That is why the measurements below report two distributions:

| Distribution | Statements for N tickets | Why it matters |
|---|---|---|
| Realistic (users reused across tickets) | `1 + distinctUsers + distinctApps` | What a normal dataset does |
| Worst case (every reference distinct) | `2N + 1` | What the original audit measured |

A dataset that reuses users makes the endpoint look far healthier than it is, which is exactly how
this defect survived.

---

## 2. Query scaling: before and after

Realistic distribution, one `GET /api/tickets`:

| Tickets | Before SQL | After SQL | Before latency | After latency | Before payload | After payload |
|--:|--:|--:|--:|--:|--:|--:|
| 50 | 31 | **2** | 22 ms | **14 ms** | 26,643 B | **10,716 B** |
| 200 | 121 | **2** | 55 ms | **16 ms** | 107,619 B | **10,678 B** |
| 500 | 301 | **2** | 134 ms | **23 ms** | 269,100 B | **10,780 B** |
| 1,000 | 601 | **2** | 260 ms | **14 ms** | 538,952 B | **10,842 B** |
| 5,000 | 701 | **2** | 244 ms | **15 ms** | 2,721,988 B | **11,024 B** |

Worst-case cardinality (every ticket references a distinct raiser, assignee and application) — the
distribution the audit measured:

| Tickets | Before SQL | After SQL | Before latency | After latency |
|--:|--:|--:|--:|--:|
| 50 | 101 | **2** | 24 ms | **18 ms** |
| 200 | **401** | **2** | 109 ms | **20 ms** |
| 500 | 1,001 | **2** | 326 ms | **21 ms** |
| 1,000 | 2,001 | **2** | 264 ms | **17 ms** |

**The statement count is now a constant 2 at every size and every cardinality** - one page query and
one count query. At 1,000 tickets with distinct references that is 2,001 → 2, a 1,000× reduction.

`GET /api/tickets/{id}` was already one statement and still is; it now uses a projection, so it loads
no managed entities.

### Honest caveat on bulk retrieval

A page is far cheaper than the old full dump, but *fetching every row* is now more work in aggregate:
5,000 tickets at the default page size needs 250 requests (≈500 statements) rather than one request
with 701. Pagination optimises interactive latency, memory and payload - not bulk export. A larger
page size (`size=100`, the cap) reduces that to 50 requests. If bulk export becomes a requirement it
should be a separate streaming endpoint, not an unbounded list.

---

## 3. Concurrency: before and after

1,000 tickets, all requests released simultaneously, ~40–50 requests per level. Times in ms.

| Concurrency | Before avg | After avg | Before p95 | After p95 | Before p99 | After p99 | Before total | After total |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 1 | 341 | **42** | 931 | **72** | 1,376 | **205** | 13,700 | **1,724** |
| 5 | 963 | **82** | 2,367 | **473** | 2,736 | **659** | 9,452 | **1,026** |
| 10 | 1,617 | **45** | 2,862 | **77** | 3,798 | **321** | 7,778 | **1,109** |
| 25 | 2,718 | **23** | 6,841 | **37** | 7,690 | **67** | 8,992 | **1,403** |
| 50 | 3,920 | **27** | 6,164 | **52** | 6,176 | **115** | 7,054 | **1,351** |

No failures at any level, before or after.

Degradation from 1 → 50 concurrent went from roughly **11× to roughly 1×** - at these volumes the
endpoint stopped being the bottleneck. The residual variance (the concurrency-5 row is noisier than
the 25 and 50 rows) is JIT, GC and autovacuum noise on a developer machine, not a real inversion;
single-digit-millisecond work is dominated by scheduling jitter. Treat these as order-of-magnitude
comparisons.

The audit's original figures (10 concurrent → 15.4 s worst case, ~50 s aggregate) were taken against
5,100 tickets rather than 1,000, so they are not directly comparable to this table; the before column
here is the like-for-like re-measurement.

---

## 4. The change that mattered most: a plan-stable query

The first implementation used the familiar single-query idiom for optional filters:

```sql
where (:status is null or t.status = :status) and ...
```

Convenient, and it did fix the N+1. But it is **not plan-stable**. Measured against 20,000 tickets,
same SQL, same data:

| Plan | Plan shape | Buffers |
|---|---|--:|
| Custom (parameters known) | `Index Scan using idx_tickets_created_at_id` | 145 |
| Generic (parameters opaque) | `Seq Scan` + hash joins + top-N heapsort | **86,026** |

A 590× regression, reachable because the PostgreSQL JDBC driver promotes a statement to a server-side
prepared statement after five executions, at which point PostgreSQL may choose a generic plan.
`plan_cache_mode = auto` avoided it here only because the generic estimate happened to look more
expensive - a property of current cost estimates, not a guarantee.

So the list query is built with the Criteria API in `TicketQueryRepositoryImpl`, emitting only the
predicates actually supplied. Unfiltered requests produce SQL with no `WHERE` clause at all; a
status+assignee request produces exactly `where t1_0.status=? and at1_0.id=?`. Re-measured with the
generic plan **forced**:

```
Index Scan using idx_tickets_created_at_id on tickets t1_0   145 buffers
```

The cliff is gone, not merely avoided.

---

## 5. Indexes (migration V12)

Designed from the queries the API actually issues, measured with `EXPLAIN (ANALYZE)` against 20,000
realistic tickets, and kept only where the planner chose them and buffer counts fell.

Before any index, every list query read the whole table to return 20 rows: `Seq Scan` + top-N
heapsort, **411 buffers**.

| Index | Query supported | Plan after | Buffers before → after |
|---|---|---|--:|
| `idx_tickets_created_at_id (created_at DESC, id DESC)` | default page, `ORDER BY created_at DESC, id DESC LIMIT n`; also serves status and priority filters at this volume | `Index Scan` | 411 → **3** (bare) / **145** (with joins) |
| `idx_tickets_assigned_to_created_at (assigned_to, created_at DESC, id DESC)` | an agent's queue, `WHERE assigned_to = ?` | `Bitmap Heap Scan` | 414 → **55** |
| `idx_tickets_raised_by_created_at (raised_by, created_at DESC, id DESC)` | "tickets I reported" | `Bitmap Heap Scan` | 414 → **55** |
| `idx_tickets_status_created_at (status, created_at DESC, id DESC)` | the **count** behind a status-filtered page | `Index Only Scan` | 419 → **25** |

Notes kept deliberately honest:

- **`idx_tickets_status_created_at` is not used for the status-filtered page query** at 20,000 rows.
  The planner prefers walking `idx_tickets_created_at_id` in sort order and filtering, because `OPEN`
  matches ~12% of rows and only 20 are needed. The index is justified by the count (index-only scan),
  and both indexes exist so the optimizer can switch as selectivity changes. The plan choice is left
  to PostgreSQL.
- **The unfiltered count** is an `Index Only Scan using tickets_pkey` at 57 buffers rather than a
  405-buffer `Seq Scan`. That depends on the visibility map being current, i.e. on autovacuum keeping
  up. On a write-heavy table a stale visibility map pushes it back toward a sequential scan.
- The two foreign-key indexes are a second win: PostgreSQL does not index referencing columns
  automatically, so they also make the `ON DELETE RESTRICT` checks from V11 cheap.

### Rejected after measurement

- **`idx_tickets_priority_created_at`** was created and measured. `pg_stat_user_indexes` reported
  `idx_scan = 0`: with four distinct values over 20,000 rows the planner always preferred
  `idx_tickets_created_at_id` (4 buffers). 856 kB of index to maintain on every write for no measured
  read benefit, so it was **not** shipped.
- **`ticket_number`** needed nothing. `tickets_ticket_number_key` (the unique constraint from V3)
  already serves lookups as an `Index Scan` at 3 buffers. Sequence-based *generation* (V10) and
  lookup *indexing* are separate concerns and the lookup side was already correct.
- `updated_at`, `application_id`, `module_name`, `issue_type`, `business_impact`, date ranges, and
  `ticket_comments`/`ticket_attachments` foreign keys: no query filters on them yet.

---

## 6. Pagination

### Strategy: offset, deliberately

Offset pagination was chosen over keyset/cursor pagination because the measured degradation does not
justify the added complexity. 20,000 tickets, page size 20:

| Page | Offset | Latency |
|--:|--:|--:|
| 0 | 0 | 52 ms |
| 100 | 2,000 | 68 ms |
| 500 | 10,000 | 74 ms |
| 900 | 18,000 | 95 ms |

Deep pages cost more because the index must be walked past the offset (`EXPLAIN` shows 5,020 index
entries scanned for `OFFSET 5000`), but at this scale the penalty is tens of milliseconds.

**Where cursor pagination would start to pay off:** when offsets reach the high hundreds of thousands,
or when clients page continuously through a large, actively-changing result set (where offset paging
can also skip or repeat rows as data shifts beneath it). Neither applies yet. Revisit if the table
passes roughly a million rows or if a client needs stable deep iteration.

### Contract

- `page` (default 0), `size` (default 20, **hard cap 100**), `sort` (`property` or `property,asc|desc`).
- Sortable properties are whitelisted: `createdAt`, `updatedAt`, `priority`, `status`,
  `ticketNumber`, `id`. Anything else is a 400.
- `id` is always appended as a tiebreaker. Without it, ordering by `createdAt` alone is
  non-deterministic across ties and offset paging can skip and repeat rows.
- Sorting and limiting happen in PostgreSQL, never in Java.

The cap matters: without it the endpoint would still be unbounded, because a client could simply ask
for `size=1000000`.

---

## 7. Fetch strategy: deliberately unchanged

`Ticket`'s three `@ManyToOne` associations remain `EAGER`. This was a measured decision, not an
oversight:

- **Read paths no longer load entities at all.** Both the list and the single-ticket read go through
  `TicketRow` projections, so fetch strategy is irrelevant to them.
- **Write paths load exactly one ticket** via `findById`, which Hibernate's entity loader resolves
  with a single joined statement - measured at **1 statement**. Switching to `LAZY` would not reduce
  that, because `mapToResponse` reads all three association names anyway; it would just move the same
  work into separate selects.

Flipping everything to `LAZY` would therefore add `LazyInitializationException` risk for no measured
gain. There are no `@OneToMany` collections on `Ticket`, so there is no collection-fetch problem to
solve.

---

## 8. Connection pool: deliberately unchanged

HikariCP defaults are retained (`maximumPoolSize = 10`). The evidence:

```
50 concurrent requests:  hikari max=10 active=0 idle=10 total=10 awaitingConnection=0
                         avg 27 ms, p99 115 ms, 0 failures
```

`threadsAwaitingConnection` stayed at 0 and no request failed, so the pool is not the constraint. A
larger pool would not help and can actively hurt: more concurrent sessions than PostgreSQL has cores
increases lock and buffer contention. Per the phase rule, no evidence supports a change, so none was
made.

If a future workload does queue for connections, the correct response is to look at what holds
connections for too long first, and only then size the pool against the database's actual capacity.

---

## 9. Transaction read paths

All read methods were already `@Transactional(readOnly = true)` from Phase 2, and that is unchanged.
It is worth more now than it was: `readOnly` lets Hibernate skip dirty-check bookkeeping, and with
projections there are no managed entities to track in the first place. No write transaction semantics
were touched.

## 10. Memory

The previous implementation materialised every `Ticket` entity plus its associated `User` and
`Application` entities into the persistence context for one request - at 5,000 tickets, tens of
thousands of managed objects, then a 2.7 MB response string. The projected page holds at most 20
flat records and produces a ~11 KB response. Pagination is the protection; no cache was added.

---

## 11. Search: not implemented, not added

The application has **no ticket search** - no `LIKE` queries, no text filter, nothing to optimise.
Adding one would be a new feature rather than a performance change, so Phase 3 did not.

For the record, when it is specified: PostgreSQL is sufficient and no external search engine is
warranted at any volume this application is likely to see. A `tsvector` GIN index over
`title || description` handles full-text; `pg_trgm` handles substring and fuzzy matching. Both should
be chosen against a real query pattern, the same way the V12 indexes were.

---

## 12. Remaining bottlenecks

Honest list of what still limits this application:

1. ~~**`GET /api/users` and `GET /api/applications` are still unbounded.**~~ **Fixed in Phase 6** -
   see section 15. Both now page with the same contract as the ticket list.
2. **The count query is now the larger half of a list request.** The page is ~3 buffers; the
   unfiltered count is ~57. It is skipped when the total is implied, but for deep pages it always
   runs. If it becomes dominant, options are an approximate count from `pg_class.reltuples` or
   dropping to a no-count `Slice`.
3. **Deep offset pages** cost proportionally more (section 6).
4. **No HTTP caching.** No `ETag` or `Cache-Control`, so repeat reads always hit the database.
5. **Single application instance assumed.** Nothing prevents horizontal scaling - the app is
   stateless and ticket numbering is database-backed - but it has never been tested behind a load
   balancer.
6. **Write paths were not optimised** and were deliberately left alone: ticket creation still costs
   several statements (actor lookup, application lookup, sequence, insert, audit insert). Phase 2's
   atomicity guarantees depend on that shape.
7. **`ticket_history_tracking` grows without bound** and has no retention policy.

## 13. What was measured, and what was not

**Measured:** SQL statement counts, single-request latency, response size, and latency distributions
at 1/5/10/25/50 concurrent requests, on one developer machine against local PostgreSQL 18, with
datasets up to 20,000 tickets.

**Not measured, and therefore not claimed:**

- Behaviour beyond 50 concurrent requests, or with sustained load over time.
- Any dataset larger than 20,000 tickets.
- Behaviour on production-grade hardware, over a real network, or with PostgreSQL tuned differently.
- Multiple application instances, or anything behind a load balancer.
- Write-path throughput under concurrency.
- Memory and GC behaviour under sustained load (no profiler was attached).

**This phase does not establish that the system supports any particular number of users.** It
establishes that the ticket list's database cost no longer grows with the size of the table, which is
the property that previously made growth impossible. Capacity claims need a real load test against
production-like infrastructure.

## 14. Reproducing these numbers

```bash
mvnw test -Dtest=TicketListBenchmark -Djunit.jupiter.conditions.deactivate=*
```

Requires Docker (see docs/TESTING.md). Individual benchmarks: `#datasetScaling`,
`#worstCaseScaling`, `#concurrencyScaling`, `#singleTicketRetrieval`.

The always-on guard that the scaling property does not regress is
`TicketListBaselineTest.pageCostsConstantStatements`, which asserts statement counts rather than
timings so it cannot flake.

---

## 15. The user directory and application catalogue (Phase 6)

`GET /api/users`, `GET /api/applications` and `GET /api/applications/active` were the last unbounded
collection endpoints - item 1 of section 12 until this phase. They now use the same contract as the
ticket list.

### What was wrong, and what was *not* wrong

Only one of the ticket list's two defects applied here:

| Defect | Ticket list | Users / applications |
|---|---|---|
| Unbounded result set | yes | **yes** |
| N+1 on associations | yes (2N+1) | **no** |

Neither `User` nor `Application` declares a single `@ManyToOne`, `@OneToMany` or `@ManyToMany`
(verified by grep, not assumed), so there was never an N+1 to fix. A page is naturally two
statements - the page query and the count - and was so before any change. The defect was purely
response size: the audit measured **787 KB for 5,103 users**, growing linearly and without limit.

This distinction matters, because it meant the fix needed no fetch-strategy work at all.

### Measured: the cost of a page

`EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` against **50,000 users** and **500 applications** on
PostgreSQL 18. Name distribution is deliberately realistic: 50,000 rows share only 200 distinct full
names, so `full_name` is heavily duplicated.

| Query | Before V14 | After V14 |
|---|--:|--:|
| Users, page 0, `ORDER BY full_name, id LIMIT 20` | Seq Scan + top-N heapsort, **1,105 buffers, 55.6 ms** | Index Scan, **23 buffers, 0.16 ms** |
| Users, page 100 (`OFFSET 2000`) | parallel Seq Scan + heapsort, 1,145 buffers, **113 ms** | Index Scan, 2,032 buffers, **2.2 ms** |
| Users, exact count | Index Only Scan on `users_pkey`, 139 buffers, 10.8 ms | unchanged |
| Users, `ORDER BY email, id` | Index Scan + Incremental Sort, 9 buffers | unchanged (already covered) |
| Applications, page 0 | Seq Scan + heapsort, **5 buffers, 0.35 ms** | *no index created* |
| Applications, active only, page 0 | Seq Scan + heapsort, **5 buffers, 0.34 ms** | *no index created* |

**48x fewer buffers on the first page, and the sort disappears** - the index already holds the order.

### Indexes: one created, one rejected

Migration **V14** creates exactly one index, `users (full_name, id)`, and records the reasoning inline.

Rejected after measurement: `applications (app_name, id)`. A 500-row reference table occupies 5 pages,
so the page already costs 5 buffers; there is no room for an index to help, and it would be write
overhead for nothing. Also deliberately not created: anything for `email` or `employee_code` (the V1
`UNIQUE` constraints already index them), for `role` or `active` (7 and 2 distinct values), or for the
count query (already an index-only scan).

The same discipline as V12: an index exists only where a measurement shows the planner chose it and
the buffer count fell.

### Honest note: deep offsets get *more* expensive in buffers

At `OFFSET 2000` the index makes execution **51x faster** but reads **more** buffers (1,145 -> 2,032),
because an ordered walk of 2,020 rows replaces a single sort of 50,000. Offset pagination is
inherently O(offset) with or without an index. Keyset pagination is the real fix for deep iteration;
it is a contract change and was not attempted here. This is the same trade-off already recorded in
section 6, now confirmed on a second table.

### Why a projection for users but not for applications

`UserRow` exists for a **security** reason, not a performance one: it keeps `password_hash` out of the
query entirely, so a directory listing never has the hash in memory. `UserListBaselineTest` asserts
this against the SQL text that reaches the driver, and asserts the inverse too - that login still
reads the hash, so the projection cannot have been applied where verification needs it.

`Application` gets no projection: its four columns are exactly the four the response carries, so
loading the entity already reads nothing spare. Adding a type there would be ceremony.

### Reproducing the Phase 6 numbers

There is no benchmark class for these endpoints; the figures above came from `EXPLAIN` in `psql`
against a seeded throwaway database. The always-on regression guards are
`UserListBaselineTest` (statement counts stay constant from 50 to 500 rows; the listing SQL never
mentions `password_hash`) and `UserAndApplicationPaginationTest` (bounds, caps, stable paging,
sort whitelist, headers, and that authorization did not loosen).
