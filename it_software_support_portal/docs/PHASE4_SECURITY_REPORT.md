# Phase 4 — Security, Identity, Authentication and Authorization

**Completed:** 2026-10-03 · **Documented:** 2026-10-04 · **Design reference:** `docs/SECURITY.md`

Phase 4 replaced the transitional `X-User-Id` identity mechanism with a production-grade security
foundation, while preserving the correctness guarantees from Phase 2 and the performance guarantees
from Phase 3.

---

## 1. Implemented

### Authentication
- Spring Security with the OAuth2 resource server, issuing and validating **HS256 JWT bearer tokens**
  through Spring Security's own Nimbus `JwtEncoder`/`JwtDecoder`. No hand-rolled JWT handling.
- `POST /api/auth/login` authenticates email and password against the `users` table and returns a
  signed token. The only functional public endpoint.
- Stateless session policy; no cookie is created anywhere.
- Signing key externalised and required to be at least 32 bytes. When unset, a random per-JVM key is
  generated with a loud warning — there is no insecure default and no committed key.

### Identity
- `RequestHeaderCurrentUserProvider` (the `X-User-Id` reader) was **deleted**, not bypassed.
- `AuthenticatedCurrentUserProvider` resolves the acting user from the verified token's `sub` claim,
  with no per-request database query.
- **No business service changed.** The `CurrentUserProvider` seam absorbed the entire mechanism swap,
  which was the reason it was introduced in Phase 2.

### Authorization
- 9 `Permission` values mapped from the **7 roles that already existed**. No role was invented, added
  or renamed.
- `@EnableMethodSecurity` with `@PreAuthorize` on 14 controller methods; filter-chain default is
  `anyRequest().authenticated()`, so new endpoints are protected until someone decides otherwise.
- Authorities derived from the role on every request rather than read from the token, so a mapping
  change takes effect immediately.

### Resource-level authorization
- Single-ticket visibility enforced in `TicketServiceImpl.requireVisibility`, using ownership ids that
  travel on the existing projection — **no additional query**.
- Collection-level ownership predicate pushed into the SQL for callers without `TICKET_READ_ALL`,
  ANDed with their own filters so it can only narrow a result set.
- `GET /api/users/{id}` restricted to `USER_READ` holders or the user's own record.

### Passwords
- BCrypt via Spring Security's `PasswordEncoder`.
- `users.password_hash` added by `V13`, nullable so pre-existing accounts fail closed.
- Uniform 401 for every credential failure; dummy-hash verification keeps the cost of a nonexistent
  account comparable to a real one.
- No hash or plaintext in any DTO, response or log.
- `BootstrapAdminInitializer` creates one administrator on first start — opt-in, once only, never logs
  the password.

### Error handling
- RFC 7807 problem documents for 401 and 403, written from inside the filter chain by
  `SecurityProblemWriter` so security failures have the same response shape as 404 and 409.
- Explicit `AccessDeniedException` mapping in `GlobalExceptionHandler` (see §7, finding 1).
- Phase 2's transitional **400** for a missing identity replaced by **401**.

### Configuration
- All secrets externalised to environment variables.
- CORS rebuilt inside `SecurityConfig`: exact origins, `allowCredentials=false`, restricted request
  headers, and **pagination headers exposed** (see §7, finding 2).
- Swagger exposure made configurable via `app.security.swagger-public`.

---

## 2. Verified — test results

Final clean run of the committed test suite:

```
Tests run: 181, Failures: 0, Errors: 0, Skipped: 169
BUILD SUCCESS
```

**This result must be read with its skip count.** Docker is not available on the development machine
used, so the Testcontainers-backed integration tests were **skipped, not executed** — only the 12
Docker-free tests ran. A green build in this configuration means "not contradicted", not "verified".
See `docs/TESTING.md`.

The integration tests *were* executed, against a disposable local PostgreSQL 18 database using a
temporary harness override that was removed afterwards:

```
Tests run: 182, Failures: 0, Errors: 1
```

The single error was `usesContainerDatabaseNotLocalhostDevDatabase`, which asserts a Testcontainers
container is in use and therefore cannot pass without one. Every other test passed, including all 37
security tests. The override left no trace in the committed source.

**Security test counts** (`src/test/java/.../security/`): `AuthenticationSecurityTest` 13,
`AuthorizationSecurityTest` 10, `ResourceAccessControlTest` 14 — **37 security tests**.

---

## 3. Security verification — observed live results

Exercised against a running instance with a seeded administrator, support agent and two employees.

| Check | Result |
|---|---|
| `GET /api/users` with no token | **401** |
| `GET /api/users` as `ADMIN` | **200** |
| Login, wrong password | **401**, body `{"detail":"Invalid email or password.", …,"status":401,…}` |
| Login, unknown email | **401**, **byte-identical body** to wrong password |
| Bootstrap admin login | **200**, `role: ADMIN`, `tokenType: Bearer`, `expiresIn: 1800` |
| Ticket raised by Emma (`EMPLOYEE`) | `raisedBy: "Emma Employee"` — the authenticated caller, not user 1 |
| `X-User-Id` sent alongside a valid token for a different user | **ignored**; `raisedBy` remained the token's user |
| `X-User-Id` sent alone, no token | **401** |
| **IDOR**: other employee reads Emma's ticket by id | **404** |
| Emma reads her own ticket | **200** |
| Support reads any ticket | **200** |
| `EMPLOYEE` attempts assign | **403** |
| Support attempts assign | **200** |
| `EMPLOYEE` attempts status change | **403** |
| Support attempts status change | **200** |
| Ownership-restricted list: Emma / other employee / support | `X-Total-Count` = **1 / 0 / 1** |
| Passwords in any API response | none; no `password`, no `$2a$` |
| Signing key in the repository | none — `app.security.jwt.secret` is empty in committed config |

### Audit actor versus target

```
action_type     | old_value | new_value     | actor         | actor_role
----------------+-----------+---------------+---------------+-----------
CREATED         |           | OPEN          | Emma Employee | EMPLOYEE
ASSIGNED        |           | Emma Employee | Sam Support   | IT_SUPPORT
STATUS_CHANGED  | OPEN      | ASSIGNED      | Sam Support   | IT_SUPPORT
STATUS_CHANGED  | ASSIGNED  | IN_PROGRESS   | Sam Support   | IT_SUPPORT
```

The actor is the authenticated caller throughout; the assignment target appears as `new_value`. Phase
2's correction of `changed_by` from assignee to actor did not regress.

---

## 4. Performance regression verification

The Phase 3 benchmark was re-run with security enabled.

| Metric | Phase 3 | Phase 4 (security enabled) |
|---|---|---|
| SQL statements per ticket-list page | 2 | **2** (unchanged at 50 / 200 / 500 / 1,000 / 5,000 tickets) |
| Response payload | ~11 KB | **~11 KB** (10,716 B at 50 → 11,024 B at 5,000) |
| Single-page latency | 14–23 ms | **16–20 ms** |
| 50 concurrent, average | 27 ms | **28 ms** |
| 50 concurrent, p99 | 115 ms | **56 ms** |
| Hikari pool under 50 concurrent | `awaitingConnection=0` | **`awaitingConnection=0`** |
| Worst-case cardinality (all references distinct) | 2 statements | **2 statements** |

Pagination, the response contract and the indexed filters are all intact. Concurrency figures are
approximately equivalent; differences at this scale are machine noise rather than signal.

A genuine security-induced regression was found and fixed during the phase — see §7, finding 3.

---

## 5. Database migrations

**One migration**, additive, nothing earlier modified:

| Version | Script | Purpose |
|---|---|---|
| V13 | `add_user_credentials.sql` | Adds nullable `users.password_hash` for BCrypt storage |

Verified: fresh-database migration applies V1–V13 and Flyway validates; `FlywayMigrationTest` asserts
the recorded script list matches the files on disk. Details in `docs/MIGRATIONS.md`.

---

## 6. Known limitations

**Technical limitations of the current design.** Each is a deliberate boundary, documented so it is a
decision rather than an oversight. None is a live vulnerability.

| Limitation | Consequence | Mitigation today |
|---|---|---|
| **No token revocation before expiry** | A deactivated user keeps read access for the remaining token lifetime; new logins are refused immediately | Short TTL (30 min default) |
| **No refresh-token flow** | Clients re-authenticate on expiry | — |
| **Single symmetric signing key, no rotation** | Changing the secret invalidates every outstanding token at once | — |
| **`GET /api/users` unpaginated** | Returns every user row | Now authenticated and `USER_READ`-gated, so no longer an anonymous disclosure |
| **`GET /api/applications` unpaginated** | Returns every application row | Authenticated and `APPLICATION_READ`-gated |
| **No rate limiting or brute-force protection** | Nothing throttles online password guessing or request flooding | BCrypt cost and uniform failure responses; the strongest candidate for the next security increment |
| **Security headers only partly reviewed** | `X-Frame-Options` and `X-Content-Type-Options` are set; HSTS, CSP and `Referrer-Policy` are not | HSTS depends on a TLS termination decision |
| **Department-level authorization unresolved** | `MANAGER`, `HOD`, `DIRECTOR` have requester-level permissions only | Fails closed; see §8 item 3 |

---

## 7. Findings discovered during Phase 4

Three issues were found and fixed while implementing security. Recorded because each is the kind of
thing that would otherwise be rediscovered painfully.

1. **`@PreAuthorize` denials would have become HTTP 500.** `@PreAuthorize` throws inside Spring MVC's
   dispatcher, so the `@RestControllerAdvice` introduced in Phase 2 saw `AccessDeniedException` before
   Spring Security's `ExceptionTranslationFilter` could. Its catch-all would have turned every
   authorization denial into a 500 — misleading clients and burying real faults. Fixed with an explicit
   handler; `AuthorizationSecurityTest.denialIsNotAnInternalError` guards it.

2. **Phase 3's pagination headers were unreadable by browser clients.** The previous `CorsConfig`
   declared no `exposedHeaders`, so `X-Total-Count` and friends were invisible to the SPA even though
   the server sent them. The replacement CORS configuration in `SecurityConfig` exposes them. This was
   a pre-existing Phase 3 gap, surfaced by rewriting CORS.

3. **The ownership restriction initially destroyed Phase 3's index usage.** Written as
   `(raiser.id = ? OR assignee.id = ?)` against the joined `users` aliases, it is logically correct but
   spans two joined relations, which PostgreSQL cannot push into the ticket indexes. Measured at 20,000
   tickets: `Seq Scan` over the whole table plus both user tables, **434 buffers**. Rewritten to compare
   the ticket's own foreign-key columns, `(tickets.raised_by = ? OR tickets.assigned_to = ?)`:
   **66 buffers** with a `BitmapOr` across `idx_tickets_raised_by_created_at` and
   `idx_tickets_assigned_to_created_at`; the matching count query dropped to 17 buffers.

---

## 8. Pending business decisions

Kept separate from the technical limitations above. **None has been decided**, and none should be
decided by inference from code.

1. **Which ticket status transitions are legal.** Any transition is currently accepted. The only
   statement of intent is one happy-path comment in `TicketServiceImpl`; `UNDER_REVIEW`, `PENDING` and
   `REOPENED` appear in no production logic.
2. **Whether reassignment resets status.** `assignTicket` forces `ASSIGNED`, so reassigning an
   `IN_PROGRESS` ticket moves it backwards.
3. **Department visibility for `MANAGER`, `HOD` and `DIRECTOR`.** Blocked on departments not existing:
   `users.department_id` references no table. These roles therefore hold requester-level permissions
   only.

Surfaced by Phase 4, and also a business question rather than a security one: **may a requester change
the status of their own ticket**, for example to close it once satisfied? The permission is currently
withheld — the safe direction — but the rule is undecided.

---

## 9. Scope confirmation

Only security, identity and authorization behaviour was intentionally changed in Phase 4, with one
necessary exception: the query rewrite in finding 3, which existed solely to keep Phase 3's performance
guarantees intact under the new authorization predicate.

Unchanged: ticket workflow and status rules, assignment behaviour, SLA, notifications, multi-tenancy,
the `TicketResponse` contract, and every migration before V13.
