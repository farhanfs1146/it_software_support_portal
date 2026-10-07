# Phase 5 — Login Abuse Protection and Security-Header Hardening

**Completed:** 2026-10-04 · **Design reference:** `docs/SECURITY.md` §5a and §8

Phase 4 left one security item explicitly open: `POST /api/auth/login` was the only unauthenticated
write endpoint and had no limit on attempts. Phase 5 closes it, and completes the header review Phase 4
had only partly done. Nothing else was touched.

---

## A. Rate-limiting architecture

A two-dimension sliding-window throttle, in process, applied only to the login endpoint.

```
POST /api/auth/login
  → ClientIpResolver.resolve(request)                      real socket address
  → LoginAttemptLimiter.checkAllowed(submittedEmail, ip)    throws 429 before DB or BCrypt
  → authenticate(...)                                      unchanged Phase 4 logic
  → recordSuccess(...) | recordFailure(...)
```

| Class | Role |
|---|---|
| `security/ratelimit/LoginAttemptLimiter` | The abstraction. Exists so a distributed implementation can replace the current one without touching `AuthServiceImpl`. |
| `security/ratelimit/InMemoryLoginAttemptLimiter` | Two `ConcurrentHashMap`s, immutable counter records, atomic updates via `compute`. |
| `security/ratelimit/TooManyLoginAttemptsException` | Carries only `retryAfterSeconds`. |
| `security/ratelimit/ClientIpResolver` | Decides what counts as the caller's address. |
| `GlobalExceptionHandler.handleTooManyLoginAttempts` | 429 + `Retry-After`, reusing the existing RFC 7807 convention. |
| `SecurityProperties.RateLimit` | Externalised thresholds. |

**Why in-process and not Redis.** The brief ruled out infrastructure introduced on reputation, and the
analysis agrees: a single instance needs no shared state, and a shared store would add a service to
deploy and keep available in exchange for nothing at the current scale. The abstraction is the hedge —
see K.

**Why in the service rather than a filter.** The limiter needs the submitted email, which lives in the
request body. A filter would have to buffer and replay the body to read it. Placing the check at the top
of `AuthServiceImpl.login` gets both the email and the address with no buffering, still runs before any
database access or password hashing, and keeps the whole decision in one readable place.

**No database migration.** The limiter holds no persistent state and touches no business table. This
phase adds no migration; the schema is unchanged at V13.

---

## B. Limits

| Setting | Value | Reasoning |
|---|---|---|
| `max-account-failures` | **5** / window | Above honest mistakes, far below useful guessing — 0.05% of a 10,000-entry list per window. |
| `max-address-failures` | **20** / window | Looser because addresses are shared: an office behind NAT is one address, and a few colleagues fumbling passwords must not lock the building out. |
| `window` | **15 min** sliding | Failures older than this are forgotten, so it is a rate not a lifetime total. |
| `block-duration` | **15 min** | Short and self-clearing. |
| `max-tracked-keys` | **50,000** per dimension | Bounds memory; eviction on overflow. |
| `trust-forwarded-headers` | **false** | No trusted proxy exists — see D. |

Both dimensions are enforced because either alone is bypassable: per-account only is defeated by
rotating addresses, per-address only by rotating accounts. The first to trip refuses the attempt.

**Accepted trade-off:** account throttling means someone who knows a colleague's email can deliberately
lock that account for up to 15 minutes. Bounded rather than eliminated — the block self-clears, and the
attacker burns their own per-address budget (20) sustaining it, so they lock themselves out first.
Eliminating it needs CAPTCHA, progressive delays or risk-based auth, which is listed as future work
rather than guessed at.

---

## C. Enumeration protection

The account dimension is keyed on **the email the caller submitted**, normalised (trimmed, lower-cased),
whether or not it matches a real user.

> Had only real accounts been throttled, a 429 would prove an account exists — reintroducing precisely
> the enumeration oracle that Phase 4's uniform 401 removed.

Verified live: five failures against a nonexistent address produce a 429 exactly as five against a real
one. Normalisation also closes a bypass — otherwise each capitalisation of an address would get its own
budget.

Phase 4's guarantee is intact. `failureModesRemainIndistinguishable` asserts that unknown account, wrong
password and deactivated account return **byte-identical** 401 bodies:

```
{"detail":"Invalid email or password.","instance":"/api/auth/login","status":401,"title":"Authentication failed"}
```

---

## D. Client address handling

`ClientIpResolver` returns `HttpServletRequest.getRemoteAddr()` — the TCP peer, unforgeable without
controlling that address.

**`X-Forwarded-For` is ignored.** It is an ordinary request header: any client can send any value, a
different one per request. Honouring it unconditionally would defeat per-address throttling outright and
would let an attacker have someone else's address throttled instead.

**Deployment assumption, verified in source:** nothing configures a reverse proxy and
`server.forward-headers-strategy` is unset, so there is no trusted hop. If the application is later put
behind a load balancer *without* enabling `app.security.rate-limit.trust-forwarded-headers`, all requests
appear to come from the proxy and the per-address limit becomes effectively global — over-throttling, not
under-throttling, which is the safe failure direction. The per-account limit continues to protect
individual passwords either way.

When the flag is enabled the resolver reads the **right-most** `X-Forwarded-For` entry — the one the
trusted proxy appended. Reading the left-most, the usual mistake, would read attacker-supplied data.

Verified live: three attempts with three different forged `X-Forwarded-For` values all returned 429.

---

## E. Memory and concurrency safety

**Concurrency.** Every mutation happens inside `ConcurrentHashMap.compute`, so each key updates atomically
under the map's own lock; the stored value is an immutable record, so no reader sees a half-written
counter. No `synchronized`, no plain `HashMap`.

- 64 concurrent failures against one account leave it blocked — no lost update let the threshold be raced
  past.
- 32 threads × 20 mixed check/record/success operations produced zero unexpected exceptions.
- 16 concurrent HTTP login attempts returned only 401 and 429 — never a 500, never a 200.

**Memory.** Keys are attacker-supplied, so unbounded growth would make the protection its own
denial-of-service vector. Entries expire once window and block have both elapsed, and a hard cap of
50,000 per dimension applies: expired entries are swept first, then the oldest windows are evicted.

**Eviction, not rejection** — refusing new keys when full would let an attacker fill the map and either
evade throttling (fail open) or lock everyone out (fail closed). Tested: 1,000 distinct keys against a
cap of 100 leaves the tracker at or below 100, and a live entry survives a sweep that discards dead ones.

---

## F. HTTP behaviour

| Situation | Status | Headers |
|---|---|---|
| Valid credentials | **200** | token in body |
| Wrong credentials, under threshold | **401** | `WWW-Authenticate: Bearer` on protected endpoints |
| Throttled | **429** | `Retry-After: 900` |
| No credentials on a protected endpoint | **401** | unchanged — *not* 429 |
| Authenticated without permission | **403** | unchanged |

Observed live, five wrong passwords then a sixth attempt:

```
attempt 1..5 -> HTTP 401
attempt 6    -> HTTP 429   Retry-After: 900
attempt 7    -> HTTP 429   Retry-After: 900

{"detail":"Too many failed login attempts. Try again later.",
 "instance":"/api/auth/login","status":429,"title":"Too many requests"}
```

`Retry-After` is a delta in seconds per RFC 9110, and is the only limiter state exposed. The body carries
no counter, threshold, remaining-attempts hint, or indication of which dimension tripped.

A **correct** password is also refused while throttled (verified: HTTP 429), because the check precedes
verification — which is what denies the attacker the BCrypt work and keeps the response
credential-independent.

**Fail-safe policy.** An unexpected fault inside the limiter is logged at ERROR and swallowed; the request
proceeds to normal password verification. Not an authentication bypass (the password is still verified),
not a 500 for an ordinary login, but loudly-reported degraded protection.

---

## G. Security headers

Reviewed against a live response. Changed: **one header added.**

| Header | Value | Decision |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Already correct — left unchanged |
| `X-Frame-Options` | `DENY` | Already correct — left unchanged |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | Already correct — and important, since a token response must not be cached. Now covered by a test so it cannot be silently removed |
| `Pragma` | `no-cache` | Already present |
| `Referrer-Policy` | `no-referrer` | **Added.** Not a Spring Security default. Stops URLs containing ticket or user ids leaking through `Referer`. Safe for both the API and the Swagger UI |

**Deliberately not added:**

- **CSP** — this application serves the Swagger UI, which needs inline scripts and styles. A CSP strict
  enough to matter would break it; one loose enough not to would be decoration. The SPA is served
  separately and should set its own.
- **HSTS** — Spring Security emits it only over HTTPS, which is already the right conditional behaviour
  (verified absent over plain HTTP). Forcing it before TLS termination is decided would be risky, since
  browsers cache HSTS and it is hard to retract.
- **`X-XSS-Protection`** — deprecated and ignored by current browsers.

**CORS/CSRF recheck:** no defect found, no change made. The API is still bearer-only and stateless, no
cookie is set anywhere, so disabling CSRF remains correct for the reason recorded in Phase 4. CORS still
uses exact origins with `allowCredentials=false` and exposes the Phase 3 pagination headers.

---

## H. Tests

**Final clean run of the committed suite:**

```
Tests run: 214, Failures: 0, Errors: 0, Skipped: 185
BUILD SUCCESS
```

**Read with the skip count.** Docker is unavailable on this machine, so the Testcontainers-backed
integration tests were **skipped, not executed** — only 29 Docker-free tests ran. A green build in this
configuration means "not contradicted", not "verified". **No claim is made that the skipped
Testcontainers tests were executed.**

**Executed run** against a disposable local PostgreSQL 18 database, using a temporary harness override
removed afterwards:

```
Tests run: 215, Failures: 0, Errors: 1, Skipped: 2
```

The single error was `usesContainerDatabaseNotLocalhostDevDatabase`, which asserts a Testcontainers
container is in use and cannot pass without one. Everything else passed.

**New in Phase 5 — 33 tests:**

| Class | Tests | Docker needed |
|---|---|---|
| `security/ratelimit/InMemoryLoginAttemptLimiterTest` | **17** | **No** — plain unit test with an injected `Clock`, so window and block expiry are exercised deterministically without sleeping |
| `security/LoginRateLimitSecurityTest` | **16** | Yes |

Coverage: normal authentication; below-threshold 401s; account throttle; address throttle; `Retry-After`
correctness; 429 body leaking nothing; correct-password-while-throttled; enumeration resistance across
unknown/wrong-password/disabled; 429 not an existence oracle; success clearing state; window and block
expiry; email normalisation; bypass attempts (changing account, changing address, spoofed
`X-Forwarded-For`, legacy `X-User-Id`, concurrency); memory bounding and sweep ordering; authenticated
traffic unaffected; 401/403 regression; headers on both normal and throttled responses.

---

## I. Regression verification

All Phase 1–4 guarantees re-run and passing in the executed run.

| Phase | Guarantee | Test class | Result |
|---|---|---|---|
| 2 | Transaction + audit atomicity, actor attribution | `TicketStatusIntegrityRegressionTest` | 10/10 |
| 2 | Optimistic locking, ticket-number uniqueness | `ConcurrencyRegressionTest` | 8/8 |
| 2 | Foreign-key integrity | `ForeignKeyIntegrityTest` | 14/14 |
| 2 | Error handling | `ErrorHandlingRegressionTest` | 15/15 |
| 2 | `assignedTo` mapping | `TicketAssignmentResponseRegressionTest` | 8/8 |
| 3 | Pagination, filtering, sorting, projection | `TicketListPaginationTest` | 18/18 |
| 3 | 2-SQL ticket listing | `TicketListBaselineTest` | 4/4 |
| 4 | Authentication, tokens, uniform failures | `AuthenticationSecurityTest` | 13/13 |
| 4 | Permission authorization, 401/403 | `AuthorizationSecurityTest` | 10/10 |
| 4 | IDOR/BOLA | `ResourceAccessControlTest` | 14/14 |
| 4 | `X-User-Id` cannot confer identity | `TicketIdentityRegressionTest` | 8/8 |
| 1 | Migration chain V1–V13 | `FlywayMigrationTest` | 11/11 |

---

## J. Performance

**Phase 3's ticket-list property is unchanged.** From the executed run:

```
[P1-1 AFTER] statements per page request
   50 tickets -> 2 statements (20 items, 10,710 B)
  500 tickets -> 2 statements (20 items, 10,780 B)
[P1-1 AFTER] 200 tickets, all references distinct -> 2 statements (was 401)
[P1-1 AFTER] payload: 100 tickets -> 10,714 B | 2,000 tickets -> 10,861 B
```

Still **2 SQL statements** per page at every size and cardinality, payload flat at ~11 KB. The
authorization predicate still compares the ticket's own foreign-key columns, so the Phase 3 indexes
remain usable.

**Login overhead.** The limiter adds two `ConcurrentHashMap` operations per attempt and **no database
traffic at all** — it is pure in-memory state. A throttled attempt is in fact *cheaper* than before,
because it returns before the user lookup and before BCrypt, which is the dominant cost of a login. No
full-application benchmark was re-run, because nothing outside the login path changed.

---

## K. Known limitations

| Limitation | Consequence | Status |
|---|---|---|
| **The limiter is in-process, not cluster-wide** | With *N* instances behind a load balancer, each enforces thresholds independently, so an attacker spreading attempts gets roughly *N*× the budget | Accepted for single-instance deployment. `LoginAttemptLimiter` is an interface so a shared-store implementation can replace it. **No claim of cluster-wide protection is made.** |
| No general request-rate limiting | Nothing throttles an authenticated client flooding the API | Open; a capacity concern rather than a credential one |
| Account lockout is abusable as a 15-minute nuisance | Someone knowing an email can lock that account briefly | Accepted and bounded — see B |
| JWT revocation window | A deactivated user keeps read access for the remaining token lifetime (30 min default); new logins refused immediately | Unchanged from Phase 4 |
| No refresh-token flow | Clients re-authenticate on expiry | Unchanged from Phase 4 |
| Single symmetric signing key, no rotation | Changing the secret invalidates all outstanding tokens | Unchanged from Phase 4 |
| `GET /api/users` unpaginated | Returns every user row | Unchanged; authenticated and `USER_READ`-gated |
| `GET /api/applications` unpaginated | Returns every application row | Unchanged; authenticated and `APPLICATION_READ`-gated |
| No CSP | Not set, because the Swagger UI needs inline scripts | Deliberate — see G |
| Department-level authorization unresolved | `MANAGER`, `HOD`, `DIRECTOR` have requester-level permissions only | Blocked on a business decision |

---

## L. Business decisions still pending

**None of these was decided or implemented.** All four remain open.

1. **Legal ticket status transitions.** Any transition is currently accepted. The only statement of intent
   is one happy-path comment in `TicketServiceImpl`; `UNDER_REVIEW`, `PENDING` and `REOPENED` appear in no
   production logic.
2. **Reassignment / status behaviour.** `assignTicket` forces `ASSIGNED`, so reassigning an `IN_PROGRESS`
   ticket moves it backwards.
3. **Department visibility for `MANAGER`, `HOD` and `DIRECTOR`.** Blocked on departments not existing —
   `users.department_id` references no table. These roles hold requester-level permissions only.
4. **Requester status-change permission.** May a requester change the status of their own ticket, for
   example to close it once satisfied? The permission is currently withheld, which is the safe direction,
   but the rule is undecided.

No `Department` or `Team` table was created. No tenancy work was started.

---

## M. Scope confirmation

**Only authentication abuse protection and narrowly scoped security-header hardening were intentionally
changed in Phase 5.**

Changed: the login path (throttle check, failure/success recording), one added response header, a 429
exception handler, and rate-limit configuration.

Unchanged: the database schema (no migration; still V13), authentication and JWT implementation,
authorization model, roles and permissions, `CurrentUserProvider`, IDOR/BOLA enforcement, the ticket
workflow, ticket-list queries and indexes, CORS and CSRF configuration, and every API contract other than
the addition of 429 as a possible login outcome. No new dependency was added.
