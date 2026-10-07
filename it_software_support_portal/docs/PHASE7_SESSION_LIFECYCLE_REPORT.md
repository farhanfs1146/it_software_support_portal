# Phase 7 — Session Lifecycle, Token Revocation and Account Controls

**Completed:** 2026-10-07 · **Design reference:** `docs/SECURITY.md` §5b, §6.5, §6.6 · **Migration:** V15

Phases 4 and 5 built authentication and authorization and then recorded, in both reports, the same set
of open items. Phase 7 closes them. Nothing outside authentication, authorization and session lifecycle
was changed.

---

## A. What was open, and what closed it

| Recorded limitation (Phases 4 / 5) | Closed by |
|---|---|
| "No refresh-token flow. Clients re-authenticate on expiry." | `POST /api/auth/refresh` — opaque, hashed, rotated, replay-detecting (§C) |
| "JWT revocation window: a deactivated user keeps read access for the remaining token lifetime." | `tv` claim versus `users.token_version`, checked inside the decoder (§D) |
| "Single symmetric signing key, no rotation. Changing the secret invalidates all outstanding tokens." | `kid`-selected key set with retained previous keys (§E) |
| No logout of any kind | `POST /api/auth/logout`, `POST /api/auth/logout-all` |
| Password change did not end sessions | It now revokes every session, including the caller's own (§F) |
| No way to deactivate, reset or re-role a user through the API | Four `USER_MANAGE` endpoints, each with revocation and lockout guards (§F) |
| Clients had to decode the token to know their own permissions | `GET /api/auth/me` |

**Still open, deliberately:** cluster-wide revocation latency at the default cache TTL (§D), forced
password change after an administrative reset, email-based self-service reset, asymmetric signing,
general request-rate limiting. All are listed in `docs/SECURITY.md` §6.7 and §11.

**Not decided, and not decided by inference:** the four business questions carried since Phase 5 —
legal ticket status transitions, whether reassignment resets status, department visibility for
`MANAGER`/`HOD`/`DIRECTOR`, and whether a requester may change the status of their own ticket. In
particular **department-level authorization remains blocked**: `users.department_id` still references no
table, so there is nothing to authorize against. Those three roles continue to hold requester-level
permissions only.

---

## B. What was added

```
security/session/
    RefreshTokenService              (interface)      issue · rotate · logout · revoke-all
    PersistentRefreshTokenService    (impl)           the lifecycle, on refresh_tokens
    RefreshTokenFamilyRevoker                         durable revocation in its own transaction
    RefreshTokenGenerator                             256-bit opaque tokens
    RefreshTokenHasher                                hex SHA-256
    PrincipalStateRegistry           (interface)      account state for the revocation check
    CachingPrincipalStateRegistry    (impl)           short-TTL cache + write-through invalidation
    AccessTokenRevocationValidator                    OAuth2TokenValidator<Jwt>
    PrincipalState · RevocationReason · InvalidRefreshTokenException
    ExpiredRefreshTokenPurge                          scheduled housekeeping
    SessionTimeConfig                                 UTC Clock bean + @EnableScheduling
security/
    JwtKeys                                           the signing-key set and rotation rules
    JwtClaims                                         claim names, shared by issuer and verifiers
entity/RefreshToken · repository/RefreshTokenRepository · repository/projection/PrincipalStateRow
```

New endpoints:

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/refresh` | the refresh token in the body |
| POST | `/api/auth/logout` | the refresh token in the body |
| POST | `/api/auth/logout-all` | access token |
| GET | `/api/auth/me` | access token |
| POST | `/api/users/{id}/password-reset` | `USER_MANAGE` |
| PATCH | `/api/users/{id}/status` | `USER_MANAGE` |
| PATCH | `/api/users/{id}/role` | `USER_MANAGE` |
| DELETE | `/api/users/{id}/sessions` | `USER_MANAGE` |

**No new dependency.** Everything uses Spring Security, Nimbus and Spring Data, all already present.
No Redis, no message broker, no cache library.

---

## C. Refresh tokens

| Decision | Reasoning |
|---|---|
| **Opaque**, not a second JWT | A self-contained token cannot be revoked — nothing is recorded server-side to compare against. The whole value of the row is that the server holds authoritative state about this one credential |
| 256 bits from `SecureRandom` | Not guessable at any attack rate, which is why this endpoint needs no brute-force throttle |
| Stored as **hex SHA-256**, plaintext never written | A database disclosure yields no usable session credentials |
| SHA-256 rather than BCrypt | The token is CSPRNG output, not a human-chosen password, so there is no guess space for a work factor to defend. BCrypt also salts, so finding a token would mean verifying against every row — a deterministic hash is what makes the unique index usable |
| 7-day TTL, access token still 30 minutes | A short access token limits what a stolen one is worth; the refresh token is what spares the user from re-entering a password every half hour |
| **Rotated on every refresh** | A stolen refresh token is useful only until the legitimate client next refreshes |
| Replay revokes the **whole family** | Revoking only the replayed token would leave the thief's successor valid, so the detection would achieve nothing |
| Session cap 10, oldest revoked | Bounds how many devices one leaked password accumulates, and bounds the table. Oldest dropped rather than newest refused, so a legitimate sign-in is never blocked by stale sessions |
| In the request **body**, never a query parameter | A query string reaches access logs, browser history and the `Referer` header. Relying on one response header to keep a credential out of three sinks is not a design |

### Replay detection signs out the innocent party too

That is intended, not a rough edge. When a token is presented twice the server cannot distinguish the
victim from the thief, and the safe reading of "this credential is in two places" is that it is
compromised. The cost is one re-authentication. Families are per-login, so a compromised chain on one
device does not end sessions on another — asserted by `reuseIsScopedToItsOwnFamily`.

### Concurrency is resolved by the database, not by locking

The revoking update is conditional on the row still being live, so of two requests presenting the same
token exactly one sees a row count of 1 and the other sees 0. The loser is a token being spent twice,
which is the replay signal. Reading the row, deciding in Java and then writing would let both racers
through — and a stolen token would rotate happily alongside the real client's.

### A bug worth recording

The first version revoked the family and *then* threw the 401. The throw rolled the transaction back,
and with it the revocation: a replayed token produced a 401 and left the thief's successor perfectly
valid. The detection logged a warning and did nothing.

`SessionLifecycleSecurityTest.replayRevokesTheWholeFamily` failed on the first run against a real
database, which is how it was found — it is not visible by reading the code, because the code reads
correctly. The fix is `RefreshTokenFamilyRevoker`, which commits in its own transaction
(`REQUIRES_NEW`) before the exception is thrown. It is a **separate bean** on purpose: Spring applies
`@Transactional` through a proxy, so a self-call would have left the annotation present and inert.
`rotate` was also reordered so account checks happen *before* the presented token is spent — a new
transaction cannot wait out the suspended one, so any path reaching the revoker must hold no row locks.

---

## D. Access-token revocation

`users.token_version` is a counter; every token carries the value current at issue time in a `tv` claim;
the validator refuses any mismatch. One increment ends every outstanding token for that user, with no
denylist to grow.

A counter rather than a "revoked before" timestamp: no clock agreement is needed between issuer and
verifier, and it cannot be defeated by skew or by two revocations inside one clock tick. A denylist of
token ids was the other candidate and was rejected — it grows without bound and needs its own eviction
policy, where a counter needs neither.

**Checked inside the `JwtDecoder`**, as a third validator after signature and standard claims. That
placement is the point: a revoked token never becomes an `Authentication`, so no controller,
`@PreAuthorize` expression or `CurrentUserProvider` can act on a revoked principal. A filter would have
to be positioned correctly relative to the authentication filter to achieve the same, and could be
bypassed by any path that decodes a token directly.

Four checks, each failing closed: `sub` is a positive number; `tv` is present and numeric; the account
exists and is active; `tv` matches. The `active` check is independent of the counter, so an account
disabled by a direct database edit is refused even though nothing bumped the counter.

**Tokens issued before Phase 7** carry no `tv` claim and are refused. That is the only safe reading of
"this token predates revocation support", and it costs at most one re-login.

### The cost, and what was done about it

Revocation means consulting authoritative state on requests a self-contained JWT was meant to answer
alone. One query per request would have broken the property Phase 3 measured and Phase 5 preserved: two
SQL statements per ticket page at any size.

`CachingPrincipalStateRegistry` caches a two-column projection for `state-cache-ttl` (default 15s), and
**every revoking path invalidates the entry in the same request — twice.** Once immediately, and again
on `afterCompletion`. The second is not redundancy: the new counter value is invisible to other
transactions until the revoking one commits, so a concurrent request arriving in between would re-read
the *old* version and cache it again, and the token being revoked would keep working for up to the TTL.
A narrow window, which is what makes it the sort of defect that survives review and then fails rarely.
`afterCompletion` rather than `afterCommit` so a rollback clears anything cached mid-transaction as
well; dropping an entry is always safe, at the cost of one reload. So:

- single instance → revocation **immediate**, hot path costs **no SQL**
- the TTL bounds only changes this instance did not make: a direct database edit, or another instance
- `state-cache-ttl=0` disables caching → immediate cluster-wide revocation, at one small indexed select
  per authenticated request. **This is the correct setting behind a load balancer.**

**No claim is made that the default is cluster-wide.** With N instances and a 15-second TTL, a
revocation on one is honoured by the others within 15 seconds.

The cache is keyed on user ids taken from *signature-verified* tokens, so unlike the login limiter's
keys they cannot be invented by an attacker; a 10,000-entry cap applies anyway, with eviction rather
than rejection. Negative results are cached too, so a token naming a deleted account cannot be used to
generate query load.

---

## E. Signing-key rotation

Previously one secret, so changing it signed everyone out at once — and the practical consequence of
that cost is that the secret never gets rotated, which is the worst available outcome.

Tokens now carry a `kid`; the decoder selects from a key set. Rotation is: new secret in
`jwt.secret` with a new `jwt.key-id`, old secret moved to `jwt.previous-keys[0]`, restart, then
**delete the retained entry** once the access-token TTL has elapsed. Step three is what makes it a
rotation rather than an accumulation — a retained key is a key that can still verify a token.

Every key, retained ones included, is length-checked at startup; a duplicate or incomplete key id fails
startup with an actionable message rather than being skipped. The algorithm stays pinned to HS256 in the
decoder: honouring whatever a token's own header asks for is how `alg: none` and
public-key-as-HMAC-secret confusion attacks work.

---

## F. Account controls and lockout guards

| Endpoint | Revokes sessions | Guards |
|---|---|---|
| `PATCH /api/users/me/password` | yes, **including the caller's own** | current password verified |
| `POST /api/users/{id}/password-reset` | yes | `USER_MANAGE`; same 12-character minimum as a self-change, so a reset is not a way around the policy |
| `PATCH /api/users/{id}/status` | on deactivation | not yourself; not the last active administrator |
| `PATCH /api/users/{id}/role` | always | not yourself; not a demotion of the last active administrator |
| `DELETE /api/users/{id}/sessions` | yes | `USER_MANAGE`; self-targeting allowed |

**Why a password change ends the current session too.** It is the stricter of two defensible options.
Keeping the current session alive is friendlier, but a password changed in response to a suspected
compromise would then leave the attacker's session running if they are the one holding the current
token. The practical effect is one 401 and a normal login; the 204 contract is unchanged.

**Why a role change ends sessions.** Authorities are derived from the token's `role` claim, so a token
minted before the change still carries the old role and `@PreAuthorize` would keep honouring it. For a
promotion that is a delay; for a demotion — the case that matters — it would leave the removed
permissions usable for the rest of the token's lifetime.

**Why you cannot deactivate or re-role yourself.** Self-deactivation is immediately self-inflicted: the
next request fails and the account can no longer be re-enabled by the person who disabled it.
Self-demotion strips the permission needed to undo it, and self-promotion is plain privilege
escalation. Both are refused with a 400 naming the reason.

**Why the last active administrator is protected.** Removing them leaves nobody able to manage users,
and recovery would mean editing the database. Counted over *active* administrators specifically, since a
deactivated ADMIN row cannot sign in. This is the same invariant `BootstrapAdminInitializer` establishes,
enforced from the other end.

**Forced password change on next sign-in was deliberately not built.** It needs a flag, a gate on every
other endpoint while it is set, and a change endpoint usable without an ordinary session — a feature,
not a detail. It is recorded as future work rather than half-built.

---

## G. Housekeeping

Rows are revoked, never deleted, by the request paths, because replay detection must distinguish
"already rotated" from "never existed". `ExpiredRefreshTokenPurge` therefore deletes rows past
`expired-retention` (default 7 days) beyond expiry, on `purge-cron` (default 03:15 daily), bounded by
the `expires_at` index.

**Only expiry decides deletion, never revocation.** A revoked but unexpired row must stay findable,
because a replay of it is precisely what has to be caught — deleting it early would turn a stolen-token
replay back into "unknown token" and lose the signal. Retention exists so a detected replay is still
investigable after the tokens involved have expired.

With several instances every one runs this job. That is harmless — the delete is idempotent and the
losers delete nothing — but it is not coordinated, and no claim is made that it is.

---

## H. Tests

**138 new tests** - 57 integration and 81 Docker-free.

| Class | Tests | Docker | Covers |
|---|---|---|---|
| `security/SessionLifecycleSecurityTest` | 30 | yes | login returns both tokens; only a hash stored; rotation; spent/unknown/blank tokens; replay revokes the family; families independent; chain keeps one live token; logout idempotent and not an oracle; logout-all kills a live access token and bumps the counter; `/me` identity, permissions, session count, no secrets; token responses not cacheable |
| `security/TokenRevocationSecurityTest` | 26 | yes | deactivation, role change, own password change, administrative reset and forced sign-out each refuse an existing access token on the **next** request; direct-database deactivation honoured; reactivation restores access; every self-targeting and last-administrator guard; `USER_MANAGE` on each endpoint; 404/400 shapes; revocation scoped to one account; failed password change does not sign you out |
| `unit/RefreshTokenRotationTest` | 30 | **no** | hash-only storage, per-login families, expiry from the injected clock, rotation, replay, the concurrency loser, the expiry boundary (exclusive), deactivated and passwordless accounts, deleted user, logout idempotence, counter bump, cache invalidation, session cap and zero-cap |
| `unit/AccessTokenRevocationTest` | 19 | **no** | matching/superseded version, deactivated, deleted, missing and unparseable `tv`, string `tv`, unusable subject, identical failure text for different causes; cache hit/miss, TTL expiry, write-through invalidation, zero-TTL read-through, negative caching, null handling, bounding |
| `unit/AccountAdministrationGuardsTest` | 17 | **no** | which operations revoke and which do not, self-deactivation, self-demotion, last-active-administrator (both directions), no-op changes, promotion never blocked, 404 on unknown user |
| `unit/JwtKeyRotationTest` | 15 | **no** | key set, retained keys, short/duplicate/incomplete keys refused, ephemeral fallback; hash shape and determinism; 256-bit opaque tokens, 10,000 distinct, no structure |

Expiry and TTL rules are all driven by an injected `Clock`, so they are proved exactly and instantly
rather than by sleeping. That is also why most of this phase is verifiable on a machine with no
container runtime — see §I.

`FlywayMigrationTest` was extended: the script list now ends at V15, `refresh_tokens` is asserted to
exist, and `foreignKeysExist` asserts that `fk_refresh_tokens_user` is the **only** cascading
constraint while every other still restricts.

### Harness changes

- `refresh_tokens` added to the per-test `TRUNCATE`.
- `AbstractIntegrationTest` clears the revocation cache before each test. **Required, not hygiene:**
  every test truncates `users` and inserts fresh rows, so ids are reused while the cache is keyed on
  id — without it, a test that deactivated user 1 would leave "user 1 is inactive" cached and the next
  test's brand-new user 1 would be refused.
- Helpers added: `login`, `refresh`, `accessTokenOf`, `refreshTokenOf`, `liveSessionRows`,
  `tokenVersionOf`, `revocationReasonsFor`. `login` is used instead of `tokenFor` wherever the test is
  about the session itself — `tokenFor` mints an access token directly and deliberately creates no
  session row.

---

## I. Verification

**Committed-harness run on this machine (no container runtime):**

```
Tests run: 420, Failures: 0, Errors: 0, Skipped: 287
BUILD SUCCESS
```

**Read with the skip count.** No Docker is installed here, so every `@DatabaseIntegrationTest` is
**skipped, not executed**, in that configuration. A green build there means "not contradicted".

**Executed run** against a disposable local PostgreSQL 18 database (`itssp_phase7`, since dropped),
using a temporary harness override that was reverted afterwards:

```
Tests run: 421, Failures: 2, Errors: 3, Skipped: 1
```

**416 of 421 pass. All five that do not are accounted for, and none is a Phase 7 defect or a Phase 7
regression:**

| Not passing | Why |
|---|---|
| `ItSoftwareSupportPortalApplicationTests.usesContainerDatabaseNotLocalhostDevDatabase` | Asserts a Testcontainers container is in use. Cannot pass without one, by design. Same as every previous phase's executed run |
| `ConcurrencyRegressionTest.staleWriteIsRejected`, `.conflictMapsTo409`, `.winningUpdateRemainsIntact` | All three call `POSTGRES.getJdbcUrl()` directly to open a second connection, so they need a real container. Artifacts of the override, not of this phase |
| `TicketHistoryEndpointTest.readingTheTrailDoesNotScaleWithItsLength` | **Pre-existing test bug**, not a product defect and not caused by this phase. Verified failing **identically on pristine `2cd288e`** in a separate git worktree. It asserts 2 statements but 1 is issued: `PageableExecutionUtils` skips the count query when the first page is shorter than the page size. The property it guards — a flat statement count via the projection — does still hold. Recorded in docs/TESTING.md "Still outstanding"; left for a focused fix rather than smuggled into this phase |

**Zero failures among the 57 Phase 7 integration tests** (30 + 26 + 1) and the 81 Docker-free ones.

**No claim is made that the skipped Testcontainers tests were executed in the committed
configuration.** The executed run above is what verifies them, with the five exceptions named.

---

## J. Regression verification

| Phase | Guarantee | Result |
|---|---|---|
| 2 | Transaction + audit atomicity, actor attribution | `TicketStatusIntegrityRegressionTest` **10/10** |
| 2 | Foreign-key integrity (now including `refresh_tokens`) | `ForeignKeyIntegrityTest` **14/14** |
| 2 | Error handling and RFC 7807 shapes | `ErrorHandlingRegressionTest` **15/15** |
| 2 | `assignedTo` mapping | `TicketAssignmentResponseRegressionTest` **8/8** |
| 3 | Pagination, filtering, sorting, projection | `TicketListPaginationTest` **18/18** |
| 3 | 2-statement ticket listing | `TicketListBaselineTest` **4/4** |
| 4 | Authentication, tokens, uniform failures | `AuthenticationSecurityTest` **13/13** |
| 4 | Permission authorization, 401/403 | `AuthorizationSecurityTest` **10/10** |
| 4 | IDOR/BOLA | `ResourceAccessControlTest` **14/14** |
| 4 | `X-User-Id` cannot confer identity | `TicketIdentityRegressionTest` **8/8** |
| 4 | Password change rules | `PasswordChangeSecurityTest` **7/7** |
| 5 | Login throttling, 429, enumeration resistance, headers | `LoginRateLimitSecurityTest` **16/16** |
| 6 | Paged users and applications | `UserAndApplicationPaginationTest` **14/14** |
| 6 | No `password_hash` in listing SQL | `UserListBaselineTest` **5/5** |
| 1 | Migration chain, now V1-V15 | `FlywayMigrationTest` **13/13** |

**Nothing was weakened.** In particular the 7 roles, the 9 permissions and every ``
expression on tickets and applications are byte-identical to Phase 6 - `git diff` reports **zero**
changes to `Permission`, `RolePermissions`, `TicketController` and `TicketServiceImpl`.

Two prior-phase assertions were *updated*, both deliberately and both recorded:

- `FlywayMigrationTest.foreignKeysExist` now asserts `fk_refresh_tokens_user` is the only cascading
  constraint and that every other still restricts, rather than that none cascades.
- `UserListBaselineTest.userListingDoesNotSelectPasswordHash` was broadened: it now forbids
  `password_hash` in **every** statement touching `users` (the stronger property) and separately pins
  the revocation check to two columns. A new sibling test asserts the check costs nothing on a repeat
  request.

---

## K. Performance

Phase 3's property is the one at risk, because this phase adds a check to every authenticated request.

From the executed run:

```
[P1-1 AFTER] statements per page request
    50 tickets -> 2 statements (20 items, 10,715 B)
   500 tickets -> 2 statements (20 items, 10,775 B)
[P1-1 AFTER] 200 tickets, all references distinct -> 2 statements (was 401)
[P1-1 AFTER] payload: 100 tickets -> 10,719 B | 2,000 tickets -> 10,864 B

[P2-1 AFTER] statements per /api/users page
    51 users -> 3 statements
   501 users -> 3 statements
[P2-1 AFTER] /api/applications: 50 rows -> 3 statements | 500 rows -> 3
```

**Still 2 statements per ticket page at every size and cardinality, payload flat at ~11 KB** - the
Phase 3 numbers, unchanged. The user and application pages are 3, also unchanged (count + page + the
harness's own token mint, which happens inside the measured window).

These figures are the *steady state*: the helper issues one warm request before measuring, so the
revocation check is already cached. That is the number that matters, and a dedicated test
(`UserListBaselineTest.revocationCheckIsNotPaidOnEveryRequest`) asserts it directly - **zero**
statements mentioning `token_version` on a repeat request. The first request per user per TTL costs one
extra two-column indexed select, pinned by the sibling assertion.

**Why it holds.** The revocation check reads a cached two-column projection, so a steady stream of
requests from one user issues no SQL for it. At `state-cache-ttl=0` it becomes one indexed select per
request by design — the trade is stated in §D and is a configuration choice, not a hidden cost.

**Login** now also writes one `refresh_tokens` row and runs the session-cap check: two extra statements
per login, against BCrypt which dominates the request. **Refresh** is one indexed lookup by hash, one
conditional update, one insert and one user load.

---

## L. Scope confirmation

**Only session lifecycle, access-token revocation, signing-key rotation and administrative account
controls were intentionally changed.**

Changed: `AuthService`/`AuthServiceImpl` (refresh, logout, logout-all, me; login now writes a session
row and is a writing transaction), `AuthController`, `UserService`/`UserServiceImpl` (four new
operations; `changeOwnPassword` now revokes), `UserController`, `SecurityConfig` (key set, the third
validator, two new public paths), `JwtTokenService` (`tv`, `jti`, `kid`), `SecurityProperties`
(`session`, `jwt.key-id`, `jwt.previous-keys`), `User` (`tokenVersion`), `GlobalExceptionHandler` (one
handler), `UserRepository` (two methods), and the test harness as described in §H.

Unchanged: the authorization model — all 7 roles and 9 permissions, and every `@PreAuthorize` expression
on tickets and applications; IDOR/BOLA enforcement; `CurrentUserProvider`'s contract; the login
credential flow and its uniform 401; login rate limiting; CORS, CSRF and security headers; the ticket
workflow; ticket-list queries and indexes; pagination contracts. No existing endpoint path changed and
no existing response field was removed or renamed — `LoginResponse` only gained two fields, so a
Phase 4-era client still works.
