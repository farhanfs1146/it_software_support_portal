# IT Software Support Portal — Architecture Audit

**Audited:** 2026-10-03 · **Commit:** `2dcb36a` (main) · **Working tree:** clean, no source changes made

This audit is **evidence-based**. Every claim marked **MEASURED** was reproduced by running the
application against a throwaway seeded PostgreSQL database (`itssp_audit_fresh`, since dropped).
Claims marked **STRUCTURAL** are derived from reading the code and were not runtime-reproduced —
they are flagged as such deliberately so you can tell proof from inference.

---

## 1. Current Architecture

A single-module Spring Boot 4.0.5 / Java 21 monolith on PostgreSQL, classic layered style.

```
HTTP ──> @RestController ──> Service (interface + impl) ──> JpaRepository ──> PostgreSQL
              │                      │                                      (schema:
         @Valid DTO            entity -> DTO mapping                   it_software_support_db)
                               (private method in service)
```

**Stack (verified via `dependency:tree`)**

| Concern | Choice |
|---|---|
| Framework | Spring Boot 4.0.5, Spring Framework 7.0.6, Java 21 (`release 21`) |
| Web | `spring-boot-starter-webmvc` (blocking, Tomcat) |
| Persistence | Spring Data JPA / Hibernate, `ddl-auto=validate` |
| Database | PostgreSQL 42.7.10 driver, non-default schema `it_software_support_db` |
| Migrations | Flyway 11.14.1 (+ `flyway-sqlserver`, unused) |
| API docs | springdoc-openapi 3.0.2 (Swagger UI) |
| Boilerplate | Lombok 1.18.44 |
| **Security** | **none — no Spring Security on the classpath** |
| **Observability** | **none — no Actuator** |
| **Validation** | hibernate-validator 9.0.1 present **only transitively** via springdoc |

**Package structure** — conventional and clean:
`config` · `controller` · `dto/{request,response}` · `entity` · `enums` · `mapper` · `repository` · `service/impl` · `util`

**What is genuinely good here** (and should be preserved):

- Interface + impl separation on every service; constructor injection via `@RequiredArgsConstructor`.
- DTOs at the boundary — entities are never returned directly. This is the single most valuable
  structural decision in the codebase and makes nearly every fix below safe to apply.
- Flyway from commit #1 rather than `ddl-auto=update`, with `validate` as a guard.
- A dedicated append-only audit table (`ticket_history_tracking`) designed in from the start.
- Sensible, domain-aware enums (`IssueType`, `Priority`, `TicketStatus`, `Role`).

### Request flow, as actually implemented

`POST /api/tickets` →
`TicketController.createTicket` →
`TicketServiceImpl.createTicket` → **`userRepository.findById(1L)`** (hardcoded) →
`applicationRepository.findById(applicationId)` → `ticketRepository.save` →
`saveHistory(...)` (which **re-fetches** the ticket and user it was just handed) →
`mapToResponse`. **No transaction wraps any of this.**

---

## 2. Current Capabilities

What the system can actually do today:

| Area | Status |
|---|---|
| Create user (duplicate email/employee-code rejected) | Works |
| Get user by id, list all users | Works, unbounded |
| Create / update / list / soft-delete application | Works |
| Create ticket | Works, but **always attributed to user id 1** |
| Get ticket by id, list all tickets | Works, unbounded + N+1 |
| Assign ticket to a user | Persists correctly, but **response omits the assignee** |
| Update ticket status | Works **only if the ticket is already assigned** (else HTTP 500) |
| Ticket history/audit rows | Written for create / assign / status change — **not transactional** |
| Bean validation on request DTOs | Works (400) — but only by dependency accident |
| OpenAPI / Swagger UI | Available |
| CORS for `localhost:4200` | Configured |

**Not implemented at all** (scaffolding only, or absent):
comments (`AddCommentRequest`, `CommentResponse` are **empty classes**), attachments,
dashboard (`DashboardService` is an **empty interface**), internal notes, SLA, escalation,
notifications, search, filtering, pagination, sorting, watchers, ticket linking, teams,
departments (`departmentId` / `designationId` are dangling `BIGINT`s with **no table behind them**),
authentication, authorization, multi-tenancy.

---

## 3. Gap Analysis

### P0 — Critical

| ID | Area | Current behaviour | Problem / Risk | Direction | Indep.? |
|---|---|---|---|---|---|
| **P0-1** | Transactions | No `@Transactional` anywhere in the codebase | **MEASURED:** `PATCH /api/tickets/1/status` returned **HTTP 500**, yet the status **was committed** as `IN_PROGRESS` and **no audit row was written**. The client is told the operation failed while the data says it succeeded, and the audit trail silently loses the change. Fatal for an ITSM/compliance demo. | `@Transactional` on all service write methods; audit write must share the ticket's transaction | Yes |
| **P0-2** | Null safety | `TicketServiceImpl.java:140` → `ticket.getAssignedTo().getId()` | **MEASURED:** NPE → HTTP 500 for any status change on an unassigned ticket. Since every ticket is created `OPEN` and unassigned, **the normal first transition always fails.** | Take the actor from the authenticated principal (P0-4), not from the assignee | No — needs P0-4 |
| **P0-3** | Identity | `createTicket` hardcodes `userRepository.findById(1L)` | **MEASURED:** a ticket posted with no identity came back as `raisedBy: "Alice Admin"` (id 1). Attribution is fiction; on an empty DB every create throws. Your dev DB has **0 users**, so ticket creation is currently **100% broken** there. | Resolve the raiser from the security context | No — needs P0-4 |
| **P0-4** | AuthN/AuthZ | No Spring Security dependency; no password/credential column on `users` | **MEASURED:** `GET /api/users` returned every user with full names and emails, **no credentials of any kind**. Every endpoint — including `DELETE` — is anonymous. The `Role` enum is defined and **never enforced anywhere**. | Spring Security + JWT or session; `@PreAuthorize`; migration adding credentials | Partly |
| **P0-5** | Concurrency | `"TKT-" + System.currentTimeMillis()` | **MEASURED:** 200 simultaneous calls produced only **29 unique numbers** — 193 would violate `tickets_ticket_number_key` → HTTP 500. (60 parallel `curl`s did *not* collide: process spawn spread them 19 ms apart. Real pooled clients will not be that slow.) | DB sequence + formatted prefix, e.g. `TKT-2026-000123` | Yes |
| **P0-6** | Error handling | No `@ControllerAdvice`; every failure is `new RuntimeException("...")` | **MEASURED:** a missing ticket returns **HTTP 500**, not 404; a 150-char title returns **500**, not 400. Clients cannot distinguish "caller error" from "server fault"; monitoring cannot distinguish normal 404 noise from real incidents. | Typed exceptions + `@RestControllerAdvice` + RFC 7807 `ProblemDetail` | Yes |
| **P0-7** | Migrations | `V8__add_ticket_history_tracking_index.sql` is **recorded as applied** in `flyway_schema_history` (2026-09-29) but **exists on no branch and not on disk** | **MEASURED:** dev has index `idx_ticket_history_tracking_ticket_changed_at`; a fresh DB stops at **v7** and does not. Dev and every new environment have **already silently diverged**. Worse: creating a *different* V8 later will fail or mis-apply against existing DBs. | Re-add the file as `V8` with the exact same DDL, or renumber deliberately; then never delete an applied migration | Yes |
| **P0-8** | Integrity | **Zero foreign keys in the entire schema** | **MEASURED:** 58 constraints exist, **not one is `contype='f'`**. `raised_by`, `assigned_to`, `application_id`, `ticket_id`, `commented_by`, `changed_by` are bare `BIGINT`s. Nothing stops a ticket pointing at a deleted user or a nonexistent application; orphans are inevitable and audit rows can reference ghosts. | Additive migration adding FKs after cleaning any existing orphans | Yes |

### P1 — High

| ID | Area | Current behaviour | Problem / Risk | Direction | Indep.? |
|---|---|---|---|---|---|
| **P1-1** | Performance | `getAllTickets()` = `findAll()`, no pagination; `@ManyToOne` defaults to EAGER | **MEASURED at 5,100 tickets: one request = 7,001 SQL statements, 4,895 ms, 1.75 MB payload.** At 100 tickets it was 202 statements / 918 ms. Growth is linear and unbounded. (At low cardinality it looks fine — 61 tickets sharing one user/app took only 4 queries — which is exactly why this hides in dev.) | Mandatory `Pageable` + DTO projection or `JOIN FETCH` | Yes |
| **P1-2** | Load | Default Hikari pool (10) plus the above | **MEASURED: 10 concurrent `GET /api/tickets` → worst response 15.4 s, 50.3 s total wall clock.** Ten users saturate the pool. This is the direct answer to "thousands of users". | Fix P1-1 first, then size the pool deliberately | No |
| **P1-3** | Indexes | Only PKs and 3 unique constraints exist | **MEASURED:** `EXPLAIN ANALYZE` shows **Seq Scan** for `status`, `assigned_to`, `raised_by`, and `ticket_comments.ticket_id`; `ORDER BY created_at` is an unindexed top-N sort. Every realistic ITSM query is a full table scan. | Indexes on all FKs plus `(status, priority)` and `created_at DESC`; `CONCURRENTLY` in prod | Yes |
| **P1-4** | Lost updates | No `@Version` on any entity | **STRUCTURAL** (6 concurrent status writes all returned 200; interleaving not forced). Two agents editing one ticket → last write silently wins. | `@Version` on `Ticket`; map 409 on conflict | Yes |
| **P1-5** | Workflow | `updateStatus` accepts any value | **MEASURED:** `RESOLVED → CLOSED → OPEN → REOPENED → PENDING` all returned 200. No lifecycle exists despite the documented `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` intent in the code comment. | Explicit transition table, rejected with 409/422 | Yes |
| **P1-6** | Data quality | `resolvedAt` set on `RESOLVED`, **never cleared** | **MEASURED:** ticket ended at `UNDER_REVIEW` while still carrying `resolved_at`. Any MTTR / SLA / "resolved this week" report will be wrong. | Clear on reopen; derive metrics from history | Yes |
| **P1-7** | API correctness | `mapToResponse` has `.assignedTo(...)` **commented out** | **MEASURED:** DB `assigned_to = 2`, API returns `"assignedTo": null`. The API actively misreports assignment — an engineer sees an unassigned ticket that is assigned. | Restore with a null-safe mapping | Yes |
| **P1-8** | Validation | No `@Size` on DTOs; `tickets.title` is `VARCHAR(100)` | **MEASURED:** 150-char title → **500** from the DB. Also `User.email` is `length=150` in the entity but `VARCHAR(100)` in V1 — `ddl-auto=validate` does **not** check length, so it passes startup and fails at runtime. | `@Size` matched to DDL; align entity and migration | Yes |
| **P1-9** | Dependencies | hibernate-validator reaches the classpath **only** via `springdoc-openapi-starter-common` | **MEASURED** in the dependency tree. `@Valid` works today **by accident**. Removing or bumping springdoc would silently disable every validation annotation — no error, just unvalidated input. | Declare `spring-boot-starter-validation` explicitly | Yes |
| **P1-10** | Secrets | DB URL/user/password committed in `application.properties`; `show-sql=true`; no profiles | Credentials in git; SQL logging in prod config; one file for all environments — not deployable or organization-configurable. | Env-var placeholders plus per-profile config | Yes |
| **P1-11** | Latent break | `TicketComment` and `TicketAttachment` have their `@ManyToOne` relations **commented out** | `ticket_comments.ticket_id` / `commented_by` and `ticket_attachments.ticket_id` are `NOT NULL`, so these entities **cannot be persisted at all**. Currently unreachable (no service uses them), but the comments feature is blocked on it. | Restore relations when building comments | Yes |

### P2 — Important

| ID | Finding | Why it matters |
|---|---|---|
| **P2-1** | No pagination, filtering, sorting, or search on any endpoint | The core interaction of a support portal is "show me my open tickets" — impossible today |
| **P2-2** | `V6 ticket_history` is an **orphan table** — its entity was deleted in `799ec14` and replaced by `ticket_history_tracking` | Dead table in every environment; two tables for one concept confuses readers |
| **P2-3** | Dead code: `TicketNumberGenerator`, `TicketMapper` (both `@Component`, both unused), `AssignTicketRequest`, `UpdateTicketStatusRequest` (controller uses path/query params instead), `DashboardService`, `AddCommentRequest`, `CommentResponse`, `DashboardSummaryResponse` (all empty) | Readers cannot tell intent from reality; the unused `TicketNumberGenerator` duplicates the inline generator |
| **P2-4** | `compose.yaml` starts **MySQL + PostgreSQL + SQL Server**, all with random host ports, DB `mydatabase`/`myuser` | Does **not** match `application.properties` (`ITSoftwareSupport` / `postgres`). Unusable as-is — "easy to deploy" fails at step one |
| **P2-5** | No `Dockerfile`, no healthcheck, no `.dockerignore` | Not deployable as an artifact |
| **P2-6** | Unused dependencies: `spring-boot-starter-websocket` (zero websocket code), `flyway-sqlserver` (PostgreSQL-only config) | Attack surface and build weight for nothing |
| **P2-7** | No Actuator, health checks, metrics, correlation IDs, or structured logging | Cannot be operated or observed |
| **P2-8** | Only test is `contextLoads()`, and it **requires a live local PostgreSQL** | No CI is possible; the suite is not portable. This blocks every "changes must have tests" rule |
| **P2-9** | Inconsistent API shape: status via `@RequestParam`, assignment via two path variables, both ignoring the DTOs that exist; no `ResponseEntity`, so `POST` returns 200 not 201; `DELETE` returns `void`; no `/v1` versioning | Hard to document, hard to evolve without breaking clients |
| **P2-10** | `saveHistory` re-fetches the ticket and user it was just given | 2 extra SELECTs per write, and in `assignTicket` it runs **twice** → 4 avoidable queries per assignment |
| **P2-11** | CORS hardcoded to `localhost:4200` with `allowCredentials(true)` | Not configurable per organization/environment |
| **P2-12** | `assignTicket` always forces status to `ASSIGNED` | Reassigning an `IN_PROGRESS` ticket silently moves it backwards. Flagged, not assumed wrong — confirm the intended rule |
| **P2-13** | `tickets.module_name` duplicates `applications.module_name` as free text | No guarantee the module belongs to the application; blocks category/subcategory modelling |
| **P2-14** | No rate limiting, no audit of *reads*, no security headers | Required before any internet exposure |

### P3 — Enhancement

Watchers/followers · related and duplicate tickets · bulk operations · saved views ·
CSV/report export · webhooks · in-app notifications · attachment virus scanning · i18n.

---

## 4. Risk Summary

**Security.** The system has no authentication, so every other access-control question is moot:
there is no principal to authorize. `GET /api/users` is an anonymous PII dump (names plus emails).
No IDOR protection is possible because there is no notion of "mine". `users` has no credential
column, so auth requires a migration. **Do not expose this to any network until P0-4 lands.**

**Reliability and data integrity.** This is the most serious cluster, because it is *silent*.
P0-1 (no transactions) and P0-8 (no foreign keys) together mean the database cannot defend its own
invariants and the application does not either. The audit trail — the one feature an ITSM system
must get right — is **already provably broken** in my test run: the recorded history reads
`STATUS_CHANGED: IN_PROGRESS → ASSIGNED`, but nothing ever recorded *how* the ticket became
`IN_PROGRESS`, because that write 500'd after committing. Nothing is idempotent and nothing retries.

**Performance and scalability.** Measured, not estimated: 5,100 tickets → 7,001 queries / 4.9 s / 1.75 MB
for one call; 10 concurrent callers → 15 s. The limits are N+1 eager loading, zero pagination,
zero useful indexes, and a 10-connection pool. The app layer itself is stateless and *would* scale
horizontally once these are fixed — the bottleneck is query volume, not architecture.

**Concurrency.** Ticket numbering collides under real load (measured). No optimistic locking, so
concurrent edits lose data. Status transitions are unguarded, so two agents can drive a ticket into
a nonsensical state.

**Maintainability.** Genuinely better than average for this stage — layering and DTO discipline are
sound. The real debt is commented-out code carrying meaning (`assignedTo`, the comment/attachment
relations), duplicated mapping logic, eight dead classes, an orphan table, and a lost migration.

---

## 5. Target Architecture

Deliberately **still a modular monolith**. Nothing measured here justifies microservices, Kafka,
Redis, or Elasticsearch, and each would add operational cost against the stated goals of
operational simplicity and easy deployment. Scale comes from fixing queries, not from splitting
processes.

```
                    ┌──────────────── Spring Security filter chain ────────────────┐
                    │  JWT auth · @PreAuthorize · rate limit · correlation-ID MDC   │
HTTP /api/v1 ──────>│                                                              │
                    └──────────────────────────┬───────────────────────────────────┘
                                               v
           @RestController (thin: bind, validate, delegate, map status code)
                                               v
      Application service  @Transactional · workflow guard · optimistic locking · idempotency
                                               v
          ┌────────────────┬──────────────────┬────────────────┬──────────────────┐
     Repositories    Domain services      Audit writer    Outbox writer (same tx)
     (Specification,  (SLA clock,                               │
      DTO projection,  escalation,                              v
      @EntityGraph)    assignment)                    @Scheduled relay ──> email / webhook
                                               v
                                   PostgreSQL (FKs, indexes, FTS)
```

**Package evolution** — group by feature, keep the existing layer names inside:
`ticket/` · `user/` · `application/` · `sla/` · `notification/` · `audit/` · `security/` ·
`common/{exception,web,config}`. Existing packages stay valid; new modules land beside them.

Key decisions, each tied to a measured need:

- **Persistence.** All `@ManyToOne` → `FetchType.LAZY`; list endpoints use interface-based DTO
  projections (no entity graph loaded at all) — this directly removes the 7,001-query path.
  `Pageable` mandatory on every collection endpoint, with a server-enforced max page size.
- **Search.** PostgreSQL only. JPA `Specification` for dynamic filters, plus a `tsvector` GIN index
  on `title || description` for full text. No Elasticsearch — Postgres FTS comfortably covers
  hundreds of thousands of tickets, and adding a second datastore means solving sync and ops.
- **Tenancy.** Do **not** add multi-tenancy yet. The correct precursor is real `Department` and
  `Team` tables (today `departmentId` is a `BIGINT` pointing at nothing). Build those, and if
  multiple organizations ever become a real requirement, add a nullable `organization_id`
  discriminator with a Hibernate filter — a far smaller change than schema-per-tenant or
  database-per-tenant, and reversible. Deciding this now would be guessing.
- **Notifications.** Transactional outbox: the business transaction writes an `outbox_event` row,
  and a `@Scheduled` relay delivers it with retry and backoff. This gives at-least-once delivery
  with **no message broker**, which is the right trade at this scale.
- **SLA engine.** `sla_policy` (priority × issue-type → response/resolution minutes), business
  calendars and holidays, `ticket_sla` rows holding due timestamps and accumulated pause, and a
  scheduled breach/escalation scanner. Deadlines are **stored**, not computed per read, so the
  "SLA at risk" dashboard is an indexed query rather than a scan.
- **Observability.** Actuator health/liveness/readiness, Micrometer metrics, JSON logs with a
  correlation ID in the MDC, and business counters (tickets created, breaches, time to first response).

---

## 6. Prioritized Roadmap

The proposed phase order has been changed in two evidence-driven ways:

1. **A portable test harness comes first, before any behaviour change.** The engineering rules
   require tests for every significant change, but the only existing test needs a live local
   PostgreSQL. Until that is fixed, "every change must have tests" is unenforceable. This is the
   true prerequisite.
2. **Data integrity precedes security.** Both are P0, but the integrity bugs are *actively
   corrupting data and the audit trail on every run* (measured), whereas the missing auth is a
   build gap on a system that is not yet deployed. Integrity fixes are also smaller, API-compatible,
   and independently testable — and P0-2 / P0-3 need a principal, so auth lands immediately after
   and completes them.

| Phase | Goal | Addresses |
|---|---|---|
| **1. Test and migration foundation** | Testcontainers; migration discipline; restore lost V8 | P2-8, P0-7, P1-9 |
| **2. Correctness and integrity** | Transactions, exception handling, FKs, indexes, ticket numbering | P0-1, P0-6, P0-8, P0-5, P1-3 |
| **3. Security and identity** | Spring Security, credentials migration, roles; completes P0-2 / P0-3 | P0-4, P0-2, P0-3, P2-14 |
| **4. Ticketing core** | Pagination, filtering, projections, workflow guard, `@Version`, comments | P1-1, P1-2, P1-4…P1-8, P1-11, P2-1 |
| **5. Config and deployment** | Profiles, env secrets, working compose, Dockerfile | P1-10, P2-4, P2-5 |
| **6. Departments and teams** | Real tables behind `departmentId`; assignment routing | P2-13, tenancy precursor |
| **7. SLA and escalation** | Policies, business hours, breach tracking, outbox-driven escalation | new |
| **8. Notifications** | Transactional outbox plus relay, email, in-app | new |
| **9. Search and reporting** | Specifications, Postgres FTS, dashboard APIs | P2-1 |
| **10. Observability and load** | Actuator, metrics, correlation IDs, load tests | P2-7 |
| **11. Demo polish** | OpenAPI examples, seed data, README, idempotency keys | P2-9 |

### Safe implementation order

| Phase | Files/modules affected | Risk | Tests required | Independent? | Must NOT change |
|---|---|---|---|---|---|
| 1 | `pom.xml`, `src/test/**`, new `V8` | Very low | Harness itself proves it | **Yes** | Any production code or existing migration |
| 2 | `service/impl/**`, new `common/exception`, new `util`, new migrations | Medium — error *shape* changes (500→404/400) | Service tests incl. rollback; repository tests; `EXPLAIN` assertions | Yes, after 1 | Existing URLs, request/response field names, business rules |
| 3 | `config/`, new `security/`, `User`, controllers | **High** — every endpoint gains auth | Security tests per role; 401/403 cases | Needs 1 | Existing endpoint paths; do not weaken anything |
| 4 | `TicketController`, `TicketServiceImpl`, DTOs, repositories | Medium — list responses become paged | Workflow matrix; concurrency; query-count assertions | Needs 2, 3 | `GET /{id}` contract; enum names |
| 5 | `application*.properties`, `compose.yaml`, `Dockerfile` | Low | Container smoke test | Needs 1 | Default local dev experience |
| 6–11 | New packages, additive migrations | Low–Medium each | Per feature | Sequential | Phases 1–4 contracts |

---

## 7. Recommended First Implementation Phase

**Phase 1 — Test and migration foundation. Nothing else.**

It changes **no production code**, so it cannot alter behaviour, and it is the precondition for
honouring engineering rules #7 (every significant change must have tests), #19 (for risky changes,
create tests before modifying behaviour) and #20 (after every implementation phase, run the
relevant tests). Right now none of those can be satisfied.

**Scope**

1. Add `org.testcontainers:postgresql` (test scope) plus `spring-boot-testcontainers`. Convert
   `ItSoftwareSupportPortalApplicationTests` to a Testcontainers-backed base class so the suite runs
   with no local database and Flyway migrations are exercised from scratch on every run — which
   would have caught P0-7 automatically.
2. Declare `spring-boot-starter-validation` explicitly (fixes **P1-9**: validation currently works
   only because springdoc happens to drag in hibernate-validator).
3. Restore the lost **V8** migration file with DDL byte-identical to what dev already applied, and
   verify the existing dev DB still validates — closing the dev/fresh divergence (**P0-7**).
4. Add **characterization tests that assert today's behaviour, bugs included** — the 500 on a
   missing ticket, the NPE path, `raisedBy` always being user 1. These are the safety net that makes
   Phase 2 reviewable: when the fix lands, the test *change* is the precise record of the
   behaviour change.
5. A `docs/` note on migration rules: never delete or edit an applied migration.

**Deliberately excluded:** no new dependency beyond Testcontainers and the validation starter
(rule #9), no Redis/Kafka/Elasticsearch (rule #11), no refactoring, no API change, no bug fixes yet.

**Done when** `./mvnw test` passes on a machine with no local PostgreSQL, and the characterization
tests document current behaviour.

---

## Appendix — How the measurements were taken

- Throwaway database `itssp_audit_fresh` created on the local PostgreSQL 18 instance, migrated by
  the application's own Flyway, seeded, then **dropped**. The real dev database
  (`ITSoftwareSupport`) was accessed **read-only** and is unchanged (0 users, 0 tickets).
- App run via `spring-boot:run` on port 9099 with `--spring.jpa.show-sql=true`; SQL statements
  counted by diffing `Hibernate:` log lines immediately before and after a single HTTP request.
- Ticket-number collisions measured with a standalone 200-thread harness using the exact
  expression from `TicketServiceImpl` / `TicketNumberGenerator`.
- Index behaviour from `EXPLAIN (ANALYZE, COSTS OFF)` against 5,100 seeded tickets after `ANALYZE`.
- Constraint and index inventory from `pg_constraint` and `pg_indexes`.
- No project source file was modified during this audit.
