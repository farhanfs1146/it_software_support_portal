# Security Architecture

Written for a backend engineer joining this project. It describes what the application does today,
why those choices were made, and — just as importantly — where the current limits are.

Everything here is derived from the source. Role, permission and class names are copied from the code
rather than paraphrased, so they can be grepped.

**Scope note.** Before Phase 4 the application had no authentication at all: every endpoint was
anonymous and the `Role` enum, though stored on every user, was never used for any decision. Phase 2
had introduced an `X-User-Id` request header as an explicitly transitional, fail-closed identity
mechanism. That header is now gone from production code.

---

## 1. Authentication

### Model: application-managed, database-backed

Users live in this application's own `users` table. Authentication verifies an email and password
against that table and issues a signed JSON Web Token. There is no external identity provider.

**Why not an external IdP.** Introducing Keycloak, Entra ID or similar would add an operational
dependency — another service to deploy, configure per organisation and keep available — for a
standalone application whose identity model (`employee_code`, `email`, `role`) already exists in its
own schema. That works against the project's portability and ease-of-deployment goals. The decision
is reversible cheaply, by design; see §1.4.

### 1.1 Components

| Concern | Class |
|---|---|
| Filter chain, CORS, crypto beans | `security/SecurityConfig` |
| Token issuing | `security/JwtTokenService` |
| Claim names, shared by issuer and verifiers | `security/JwtClaims` |
| Signing-key set and rotation | `security/JwtKeys` |
| Token to authorities | `security/JwtRoleAuthoritiesConverter` |
| Current user resolution | `security/AuthenticatedCurrentUserProvider` |
| Login use case | `service/impl/AuthServiceImpl` |
| Login endpoint | `controller/AuthController` |
| 401 response | `security/ProblemDetailAuthenticationEntryPoint` |
| 403 response | `security/ProblemDetailAccessDeniedHandler` |
| Shared problem-document writer | `security/SecurityProblemWriter` |
| Externalised settings | `security/SecurityProperties` |
| First-administrator creation | `security/BootstrapAdminInitializer` |
| Uniform credential failure | `security/InvalidCredentialsException` |
| **Refresh-token lifecycle** | `security/session/RefreshTokenService` + `PersistentRefreshTokenService` |
| Opaque token generation / hashing | `security/session/RefreshTokenGenerator`, `RefreshTokenHasher` |
| Durable family revocation on replay | `security/session/RefreshTokenFamilyRevoker` |
| **Access-token revocation check** | `security/session/AccessTokenRevocationValidator` |
| Account state for that check, cached | `security/session/PrincipalStateRegistry` + `CachingPrincipalStateRegistry` |
| Expired-token housekeeping | `security/session/ExpiredRefreshTokenPurge` |
| Clock and scheduler for the above | `security/session/SessionTimeConfig` |
| Uniform refresh failure | `security/session/InvalidRefreshTokenException` |

### 1.2 Login flow

```
POST /api/auth/login   { "email": "...", "password": "..." }
  → AuthServiceImpl
      → UserRepository.findByEmail
      → PasswordEncoder.matches(raw, storedHash)        BCrypt
      → checks: hash present, password matches, account active
      → RefreshTokenService.issueFor(user)              opaque token, hash stored (Phase 7)
      → JwtTokenService.issue(user)                     Nimbus JwtEncoder
  ← 200  { accessToken, tokenType: "Bearer", expiresIn,
           refreshToken, refreshExpiresIn, userId, fullName, role }
  ← 401  for every credential failure, with one identical body
  ← 429  when login attempts are throttled (§5a)
```

The client then sends `Authorization: Bearer <accessToken>` on every other request, and exchanges the
refresh token at `POST /api/auth/refresh` when the access token expires (§5b).

`login` is a **writing** transaction since Phase 7, because it now also creates the session row: a
login that returned a refresh token which failed to persist would hand the client a credential the
server does not recognise.

### 1.3 Why stateless JWT rather than server-side sessions

- The API is consumed by a browser SPA and is otherwise stateless; the application is intended to
  scale horizontally. Sessions would require either sticky routing or a shared session store, and a
  shared store (Redis and similar) is deliberately out of scope for this project.
- No ambient browser credential means no CSRF attack surface — see §8.
- It is the same token format an external IdP would issue, which keeps §1.4 cheap.

The cost of this choice was real and is now **paid rather than accepted**. A purely stateless token
cannot be revoked before it expires, which was recorded as a known limitation through Phases 4 and 5.
Phase 7 buys revocation back with a counter and a short-TTL cache — see §6.5 — so the design is no
longer "stateless, therefore unrevocable". It is stateless on the hot path, with exactly enough state
consulted to answer "has this session ended?", and the trade is measured rather than assumed.

### 1.4 Path to an external identity provider

The seam is `SecurityConfig.jwtDecoder`. Today it validates tokens this service signed with a
symmetric key. Pointing it at an external provider's JWKS endpoint instead would mean:

1. replace the `JwtDecoder` bean's configuration;
2. retire `AuthController` and `JwtTokenService` (the provider issues tokens);
3. adjust `JwtRoleAuthoritiesConverter` to read whichever claim the provider uses for role.

**No business service, controller or repository changes.** Nothing outside the `security` package
depends on how identity is established — that is the entire purpose of §2.

---

## 2. Identity flow

```
HTTP request  (Authorization: Bearer <jwt>)
      ↓
Spring Security OAuth2 Resource Server filter
      ↓   validates signature (HS256), expiry, issuer
verified Jwt
      ↓
JwtRoleAuthoritiesConverter      role claim → Permission authorities + ROLE_<NAME>
      ↓
JwtAuthenticationToken in the SecurityContext
      ↓
CurrentUserProvider.requireCurrentUserId()      reads the sub claim
      ↓
service layer  (TicketServiceImpl, UserServiceImpl, …)
```

### Why services depend on `CurrentUserProvider`, not on Spring Security

`common/identity/CurrentUserProvider` is a one-method interface. Business services ask it who is
acting and import nothing from Spring Security. Three benefits, one of which has already been
collected:

1. **The mechanism has already been swapped once without touching a service.** The provider went from
   reading an `X-User-Id` header (Phase 2) to reading a verified token (Phase 4). Not one line of
   `TicketServiceImpl`'s business logic changed. The same seam absorbs a future move to an external
   IdP, and is where organisation/tenant context would eventually be read from the principal.
2. **Services stay testable** without constructing a security context for every unit test.
3. **There is exactly one place** where identity is established, so "can a client influence who it
   claims to be?" has one answer to audit rather than one per controller.

### No per-request database lookup in identity resolution

`requireCurrentUserId()` reads the `sub` claim and returns. It performs no query: the resource server
has already verified the token's signature, expiry and issuer. Services that genuinely need a `User`
entity load it themselves at that point — `raised_by` and `changed_by` are foreign keys, so the row
has to exist anyway. The lookup therefore happens exactly where it is required and nowhere else.

**Qualified in Phase 7.** The statement above is still true of identity resolution, but it is no longer
true of the request as a whole: the revocation check in §6.5 consults `users.token_version` and
`users.active`. That is unavoidable — revocation means consulting authoritative state — and it is
bounded by a short-TTL cache, so a steady stream of requests from one user costs **no SQL** while
revocation performed on this instance still takes effect immediately. Setting
`app.security.session.state-cache-ttl=0` trades that back for one small indexed select per request, in
exchange for immediate cluster-wide revocation. See §6.5 for the full trade.

### Fail-closed

`requireCurrentUserId()` throws `MissingUserIdentityException` when there is no authentication, the
authentication is not a verified JWT, or `sub` is absent/non-numeric/non-positive. There is no default
user and no fallback. The original defect this interface exists to prevent was a hardcoded
`findById(1L)`.

---

## 3. Authorization

### 3.1 Roles — the seven that already existed

`enums/Role` was present before Phase 4 and was **not changed**. No role was added, removed or
renamed.

`EMPLOYEE`, `MANAGER`, `HOD`, `DIRECTOR`, `IT_SUPPORT`, `DEVELOPER`, `ADMIN`

### 3.2 Permissions — nine, one per capability the API has

`security/Permission`:

| Permission | Covers |
|---|---|
| `TICKET_CREATE` | Raise a ticket |
| `TICKET_READ_OWN` | Read tickets the user raised or is assigned to |
| `TICKET_READ_ALL` | Read any ticket regardless of involvement |
| `TICKET_STATUS_CHANGE` | Change a ticket's status |
| `TICKET_ASSIGN` | Assign or reassign a ticket |
| `USER_READ` | List and read user records |
| `USER_MANAGE` | Create accounts, set roles and initial passwords |
| `APPLICATION_READ` | Read the application/module catalogue |
| `APPLICATION_MANAGE` | Create, update or deactivate applications |

There are deliberately no permissions for features that do not exist (comments, attachments, SLA,
reporting): an unused permission is an untested one.

### 3.3 Role → permission mapping

Defined in `security/RolePermissions`. Three groups, derived from the role names and kept minimal:

| Role | `TICKET_CREATE` | `TICKET_READ_OWN` | `APPLICATION_READ` | `TICKET_READ_ALL` | `TICKET_STATUS_CHANGE` | `TICKET_ASSIGN` | `USER_READ` | `USER_MANAGE` | `APPLICATION_MANAGE` |
|---|---|---|---|---|---|---|---|---|---|
| `EMPLOYEE` | ✓ | ✓ | ✓ | | | | | | |
| `MANAGER` | ✓ | ✓ | ✓ | | | | | | |
| `HOD` | ✓ | ✓ | ✓ | | | | | | |
| `DIRECTOR` | ✓ | ✓ | ✓ | | | | | | |
| `IT_SUPPORT` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | |
| `DEVELOPER` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | |
| `ADMIN` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Two grouping decisions worth understanding, both chosen to avoid inventing business rules:

- **`MANAGER`, `HOD` and `DIRECTOR` have exactly the same permissions as `EMPLOYEE`.** It is tempting
  to assume a manager should see their department's tickets. Nothing in this codebase defines that:
  `users.department_id` is a bare `BIGINT` referencing a table that does not exist. There is no
  department to scope by and no way to test such a rule, and granting it on the strength of a role's
  name would hand three roles the ability to read every ticket in the system. These roles therefore
  fail closed. This is an open business decision, not an oversight — see §11.
- **`DEVELOPER` is treated identically to `IT_SUPPORT`.** Both are IT-side roles that work tickets.
  Whether a developer should be able to *assign* as well as work is a distinction this codebase does
  not make, so none was introduced.

An unmapped role grants nothing: `RolePermissions.of` returns an empty set rather than a default, so a
role added to the enum without a mapping is powerless rather than omnipotent.

### 3.4 Why checks are written against permissions

Endpoints assert `hasAuthority('TICKET_ASSIGN')`, never `hasRole('IT_SUPPORT')`. The role remains what
is stored against a user; the permission is what the code demands. Changing which roles may assign is
then an edit to one file instead of a hunt through controllers, and a new role can be introduced
without touching a single authorization expression.

`JwtRoleAuthoritiesConverter` grants both a `Permission`-named authority per permission and a
conventional `ROLE_<NAME>` authority. Everything in the application uses the former.

**Authorities are derived per request, not read from the token.** A change to `RolePermissions` takes
effect on the next request rather than waiting for every outstanding token to expire.

### 3.5 Endpoint authorization

Method security is enabled via `@EnableMethodSecurity`; rules are `@PreAuthorize` annotations on
controller methods. The filter chain's default is `anyRequest().authenticated()`, so an endpoint added
later is protected until someone decides otherwise.

### 3.6 401 versus 403

| Situation | Status | Produced by |
|---|---|---|
| No credentials, or an invalid/expired/forged token | **401** | `ProblemDetailAuthenticationEntryPoint` |
| Login with bad credentials | **401** | `InvalidCredentialsException` → `GlobalExceptionHandler` |
| No caller identity reached a service | **401** | `MissingUserIdentityException` → `GlobalExceptionHandler` |
| Authenticated, but lacking the permission | **403** | `@PreAuthorize` → `GlobalExceptionHandler`; filter-level denials → `ProblemDetailAccessDeniedHandler` |
| Authenticated, but the resource is not theirs | **404** | the service — see §4 |

Phase 2 returned **400** for a missing identity. That was correct at the time: advertising 401 without
an authentication scheme would have implied one that did not exist. Now that authentication is real,
401 is the honest code and the 400 behaviour is gone.

> **Implementation note worth knowing.** `@PreAuthorize` throws inside Spring MVC's dispatcher, so the
> `@RestControllerAdvice` sees `AccessDeniedException` *before* Spring Security's
> `ExceptionTranslationFilter` would. Without the explicit handler in `GlobalExceptionHandler`, the
> advice's catch-all would turn every authorization denial into a 500 — misleading clients and burying
> real faults. There is a regression test for exactly this.

---

## 4. Resource-level authorization (IDOR / BOLA)

Endpoint permissions cannot express this. Every requester legitimately holds `TICKET_READ_OWN`; the
question is *which rows* that covers. The requirement is blunt: **a user must not reach another user's
resource by changing an id in the URL.**

### 4.1 Ticket access model

A ticket is accessible to:

1. the user who raised it (`tickets.raised_by`),
2. the user it is assigned to (`tickets.assigned_to`), and
3. any caller holding `TICKET_READ_ALL` — support staff and administrators, who need it to triage.

Enforced in two places in `TicketServiceImpl`:

- **Single ticket** (`getTicketById`) — `requireVisibility` compares the caller against the row's
  ownership ids.
- **Collection** (`searchTickets`) — a caller without `TICKET_READ_ALL` has an ownership predicate
  pushed into the SQL: `(tickets.raised_by = ? OR tickets.assigned_to = ?)`. It is **ANDed** with the
  caller's own filters, so a filter can only narrow the result, never widen it. Asking for another
  user's tickets returns an empty page and a `X-Total-Count` of `0` rather than leaking a count.

The ownership ids (`raisedByUserId`, `assignedToUserId`) travel on the same `TicketRow` projection the
response is built from, so the authorization check costs **no additional query**.

> The restriction compares the **ticket's own foreign-key columns**, not the joined users' primary
> keys. The two are logically identical, but the latter spans two joined relations and PostgreSQL
> cannot push it down into the ticket indexes. See `docs/PERFORMANCE.md` §5 for the measurement.

### 4.2 User record access

`GET /api/users/{id}` is readable by a caller holding `USER_READ`, or by a user reading their own
record. Without this, any authenticated user could walk `/api/users/1..n` and rebuild the directory
that locking down `GET /api/users` was meant to protect — the collection and item endpoints have to be
guarded together or neither is guarded.

### 4.3 Why 404 and not 403 for an inaccessible resource

A 403 confirms the id exists. That lets an unauthorised caller enumerate valid ids by probing, which
is itself a disclosure — "ticket 4211 exists and belongs to someone else" is information.

So a ticket or user the caller may not see is reported as **404**, indistinguishable from one that does
not exist. A test asserts the two responses are byte-identical apart from the id echoed in the
`instance` field.

This applies only to *resources*. Lacking permission for an *operation* — an `EMPLOYEE` attempting to
assign — is a plain **403**, because no resource identity is being disclosed.

---

## 5. Password security

- **BCrypt**, via Spring Security's `BCryptPasswordEncoder`. No custom cryptography, no reversible
  encryption, no plaintext. BCrypt is deliberately slow, which is the property that matters here.
- **Storage**: `users.password_hash`, added by `V13__add_user_credentials.sql`. The column is
  `VARCHAR(255)` — wider than BCrypt's 60 characters — so the algorithm can be upgraded later (Spring
  Security's delegating encoder prefixes hashes with `{id}`) without another migration.
- **Nullable by design.** Every user that existed before Phase 4 has a `NULL` hash and therefore
  **cannot authenticate**. Enabling authentication must not hand every pre-existing account a usable
  login. A password is set explicitly, by an administrator through `POST /api/users` or by the
  bootstrap administrator.
- **Uniform failure.** Unknown email, wrong password, no password set and deactivated account all
  raise `InvalidCredentialsException` and produce the same 401 body. Distinguishing them would turn
  login into an account-enumeration oracle. The real reason is logged server-side.
- **Constant-ish cost.** When no user or no hash is found, the encoder still runs against a dummy
  hash, so a request for a nonexistent account costs roughly the same as one for a real account.
  Returning early would let an attacker enumerate accounts by response time.
- **Never exposed.** `UserResponse` has no password field, so there is no path by which a hash reaches
  a client. `LoginResponse` carries no credential material. The plaintext password is never logged.
  Tests assert that neither the user APIs nor the login response contain `password` or a `$2a$` prefix.

### Bootstrap administrator

A freshly migrated database is otherwise unusable: creating a user requires `USER_MANAGE`, which
requires an account, which requires someone to create it. `BootstrapAdminInitializer` creates one
`ADMIN` on startup, guarded three ways:

1. **Opt-in** — nothing happens unless both email and password are configured. No built-in default
   account, no default password.
2. **Once only** — it runs only when no user holds `ADMIN`. It will not recreate, reset or re-enable an
   existing administrator, so it cannot be used to overwrite a password by restarting with different
   configuration.
3. **Never logs the password** — only the email and generated id.

---

## 5a. Login abuse protection (rate limiting)

`POST /api/auth/login` is the only unauthenticated write endpoint in the application, so it is the only
place an attacker can guess credentials. Phase 5 added throttling to it. Nothing else is affected:
authenticated API traffic passes through untouched.

### Where it sits

```
POST /api/auth/login
  → AuthServiceImpl.login
      → ClientIpResolver.resolve(request)
      → LoginAttemptLimiter.checkAllowed(submittedEmail, clientIp)   ← throws 429 before any DB or BCrypt work
      → authenticate(...)                                             existing Phase 4 logic, unchanged
      → recordSuccess(...)  on success   /  recordFailure(...)  on InvalidCredentialsException
```

The check runs **before** the account lookup and before BCrypt. That matters twice: a refused attempt
costs the server nothing, and the response cannot depend on whether the account exists.

| Concern | Class |
|---|---|
| Abstraction | `security/ratelimit/LoginAttemptLimiter` |
| Implementation | `security/ratelimit/InMemoryLoginAttemptLimiter` |
| Refusal signal | `security/ratelimit/TooManyLoginAttemptsException` |
| Client address | `security/ratelimit/ClientIpResolver` |
| 429 response | `GlobalExceptionHandler.handleTooManyLoginAttempts` |
| Settings | `SecurityProperties.RateLimit` |

### Thresholds, and why these numbers

| Setting | Value | Reasoning |
|---|---|---|
| `max-account-failures` | **5** per window | What actually protects one password. Comfortably above honest mistakes (a typo, an old password, the wrong one of two accounts) and far below anything useful for guessing — against a 10,000-entry password list it buys 0.05% of the list per window. |
| `max-address-failures` | **20** per window | Looser because addresses are legitimately shared. An office behind NAT is one address for everybody, so a tight per-address limit would let a few colleagues fumbling passwords lock out the building. Twenty absorbs that and still stops one host spraying many accounts. |
| `window` | **15 minutes** | Sliding. Failures older than this are forgotten, so the limit is a rate rather than a lifetime total. |
| `block-duration` | **15 minutes** | Short and self-clearing, deliberately. |
| `max-tracked-keys` | **50,000** per dimension | Bounds memory; see below. |
| `trust-forwarded-headers` | **false** | See "Client address" below. |

**Both dimensions are enforced because either alone is bypassable.** Per-account only is defeated by
spreading attempts across addresses. Per-address only is defeated by rotating addresses — a botnet, or
simply a cloud provider — while hammering the same account. The first dimension to trip refuses the
attempt.

### The lockout-abuse trade-off, stated plainly

Account-based throttling always carries a denial-of-service risk: anyone who knows a colleague's email
can deliberately fail five logins and lock that account. This is an accepted trade-off, bounded rather
than eliminated:

- the block lasts **15 minutes**, not indefinitely, and clears itself;
- the attacker must keep spending attempts to sustain it, and is burning their own per-address budget
  (20) doing so, which locks *them* out first.

An unlimited or administrator-reset-only lockout would turn this nuisance into a real outage. Reducing
it further needs a different tool — CAPTCHA, progressive delays or risk-based authentication — which is
listed as future work in §11 rather than guessed at here.

### Account enumeration is preserved

The limiter keys the account dimension on **the email the caller submitted**, normalised (trimmed,
lower-cased), whether or not it matches a real user. This is a security property, not an implementation
convenience:

> If only real accounts were throttled, a 429 would prove an account exists — reintroducing exactly the
> enumeration oracle that the uniform 401 of §5 exists to remove.

So five failures against `ghost@example.test` produce a 429 just as five against a real address do. The
Phase 4 guarantee is unchanged: unknown account, wrong password and deactivated account still produce
**byte-identical** 401 responses, and a test asserts that equality directly.

Normalisation also closes a bypass: without it, `Admin@x.test` and `admin@x.test` would each get their
own budget for the same account.

### HTTP behaviour

| Situation | Status | Headers |
|---|---|---|
| Credentials wrong, under threshold | **401** | unchanged from Phase 4 |
| Throttled | **429** | `Retry-After: <seconds>` |

The 429 body is the same RFC 7807 shape as every other error:

```json
{"detail":"Too many failed login attempts. Try again later.",
 "instance":"/api/auth/login","status":429,"title":"Too many requests"}
```

`Retry-After` is a delta in seconds, as RFC 9110 requires, and is **the only limiter state ever
exposed**. The body carries no counter, no threshold, no remaining-attempts hint, and no indication of
which dimension tripped — "your account is locked" and "your address is blocked" are different facts
about whether the account exists.

A **correct** password is also refused while throttled, because the check precedes verification. That is
intended: it is what denies the attacker the BCrypt work and keeps the response credential-independent.

### Successful login resets the state

A success clears the failure counters for both the account and the address, so someone who mistypes
three times and then signs in correctly starts clean. A success cannot clear an *active block*, because
`checkAllowed` runs first and refuses the attempt — there is no path to a success while blocked.

### Client address

`ClientIpResolver` returns `HttpServletRequest.getRemoteAddr()` — the peer of the TCP connection, which
a client cannot forge without controlling that address.

**`X-Forwarded-For` is ignored by default.** It is just a request header: any client can send any value,
including a different one per request. Trusting it unconditionally would defeat per-address throttling
entirely and would also let an attacker pin blame on someone else's address and have *them* throttled.

**Deployment assumption:** clients connect to this application directly. Nothing configures a reverse
proxy and `server.forward-headers-strategy` is not set, so there is no trusted hop whose header could be
believed. If the application is later placed behind a load balancer *without* enabling
`app.security.rate-limit.trust-forwarded-headers`, every request will appear to come from the proxy and
the per-address limit effectively becomes a global one — the per-account limit still protects individual
passwords. That is over-throttling rather than under-throttling, which is the safe direction to fail.

When the flag is enabled, the resolver reads the **right-most** entry of `X-Forwarded-For` — the value
the trusted proxy appended. Taking the left-most entry, the common mistake, reads attacker-controlled
data.

### Memory and concurrency

Keys are attacker-supplied, so unbounded growth would make this component its own denial-of-service
vector. Two bounds apply:

- **Expiry** — an entry whose window and block have both elapsed is dead and is swept.
- **A hard cap** of 50,000 keys per dimension. On exceeding it, expired entries are swept first; if
  still over, the entries with the oldest windows are evicted.

**Eviction, not rejection.** Refusing to track new keys once full would let an attacker fill the map with
junk and then either evade throttling (if we fail open) or lock everyone out (if we fail closed).
Eviction degrades gracefully: an attacker would need to sustain tens of thousands of distinct keys inside
a 15-minute window to start displacing real entries, while fighting the per-address limit throughout.

**Thread safety.** All mutation happens inside `ConcurrentHashMap.compute`, so each key updates atomically
under the map's own per-bin lock, and the stored value is an immutable record so nothing can be observed
half-written. No `synchronized` block, no non-thread-safe collection. A test fires 64 concurrent failures
at one account and asserts the threshold cannot be raced past.

### Fail-safe behaviour

Throttling is **protective, not authoritative**: it decides whether to *refuse* an attempt, never whether
to *accept* one. So an unexpected fault inside the limiter is logged at ERROR and swallowed, and the
request continues to normal password verification.

The consequences are deliberately asymmetric:

- **Not an authentication bypass** — the password is still verified by the unchanged Phase 4 logic.
- **Not a 500 for an ordinary login** — a bug in a tracking component must not take authentication
  offline.
- **Degraded brute-force protection, loudly reported** — the ERROR log is the signal to fix it.

`TooManyLoginAttemptsException` is the limiter working correctly, so it is rethrown rather than
swallowed.

### Single-instance limitation — important

> **The limiter is in-process and protects one application instance only.** It does **not** provide
> cluster-wide protection. If this application runs as several instances behind a load balancer, each
> enforces the thresholds against its own counters, so an attacker spreading attempts across *N*
> instances gets roughly *N* times the effective budget.

This is a known and accepted limitation for the current single-instance deployment, not an oversight.
`LoginAttemptLimiter` exists as an interface precisely so that a shared-store implementation — backed by
Redis or another store reachable from every instance — can replace it by providing a different bean, with
no change to `AuthServiceImpl`. That work is **not** done, and nothing here should be read as claiming
cluster-wide throttling.

### Operational note

`InMemoryLoginAttemptLimiter.clearAll()` discards every tracked attempt, clearing all active blocks at
once. It is the blunt remedy if a misconfigured threshold starts locking real users out.
`app.security.rate-limit.enabled=false` disables throttling entirely.

---

## 5b. Session lifecycle — refresh tokens (Phase 7)

### The problem

Access tokens live 30 minutes. Before Phase 7 there was nothing to exchange for a new one, so a client
staying signed in had to re-submit the password every half hour — which in practice pushes applications
towards **caching the password**, the exact opposite of what short-lived tokens are for. There was also
no way to end a session at all: no logout, and no effect from changing a password.

### The design

```
POST /api/auth/login    -> access token (30m) + refresh token (7d), new family
POST /api/auth/refresh  -> NEW access token + NEW refresh token; the one presented is consumed
POST /api/auth/logout   -> revokes that family
POST /api/auth/logout-all -> revokes every family AND bumps users.token_version
```

| Property | Choice | Reasoning |
|---|---|---|
| Format | **Opaque**, 256 bits from `SecureRandom`, base64url | Nothing to read, nothing to tamper with. A second JWT could not be revoked, because nothing would be recorded server-side to compare against — and the whole value of the row is that the server holds authoritative state about this one credential |
| Storage | **Hex SHA-256 of the token**; the plaintext is never written anywhere | A database disclosure yields no usable session credentials |
| Why not BCrypt | The token is 256 bits of CSPRNG output, not a human-chosen password | There is no low-entropy guess space for a slow hash to defend. BCrypt also salts, so lookup would mean verifying against *every* row — a deterministic hash is what makes the unique index usable |
| Lifetime | `app.security.session.refresh-ttl`, default **7 days** | Long enough not to ask for a password daily; short enough that an abandoned session dies on its own |
| Rotation | **Every refresh issues a replacement** and consumes the token presented | Bounds what a stolen refresh token is worth: it is useful only until the legitimate client next refreshes |
| Replay | A token used twice revokes the **whole family** | See below |
| Session cap | `app.security.session.max-sessions-per-user`, default **10**; oldest revoked past it | Bounds how many devices one leaked password can accumulate, and bounds the table. The oldest are dropped rather than the newest refused, so a legitimate sign-in is never blocked by stale sessions |

### Replay detection, and why it revokes the whole family

Each login starts a *family*; each refresh issues a successor inside it. If a token that has already
been exchanged is presented again, that is either a stolen token being replayed, or the real client
replaying after a thief already rotated. **The two are indistinguishable from the server**, so the safe
reading of "this credential is in two places" is that it is compromised: the entire family is revoked
and both parties re-authenticate.

Revoking only the replayed token would be pointless — the thief's successor, obtained by rotating the
stolen token, would stay perfectly valid. Signing the innocent party out is the intended cost, not a
rough edge; it is one re-authentication.

Families are scoped per login, so a compromised chain on one device does not end sessions on another.
There is a test for exactly that.

### Concurrency

Two refreshes racing with the same token is a real case — a client with parallel requests all noticing
a 401 at once. It is resolved by the **database**, not by locking: the revoking update is conditional on
the row still being live, so exactly one racer updates a row and the loser sees zero rows changed and
is treated as a replay. Reading the row, deciding in Java, then writing would let both racers through,
and a stolen token would rotate happily alongside the real client's.

A client that must tolerate parallel refreshes should serialise them — the normal expectation for a
rotating-token client.

### A bug worth recording

The first version revoked the family and *then* threw the 401. The throw rolled the transaction back,
and with it the revocation: a replayed token produced a 401 and left the thief's successor valid. The
detection logged a warning and achieved nothing. `SessionLifecycleSecurityTest` caught it; the fix is
`RefreshTokenFamilyRevoker`, which commits in its own transaction (`REQUIRES_NEW`) before the exception
is thrown. `rotate` is ordered so that every path reaching it holds no row locks, since a new
transaction cannot wait out the suspended one.

### Why refresh is not rate-limited

Login throttling exists because a password is low-entropy and guessable. A refresh token is 256 bits
from a CSPRNG — there is nothing to guess, so a limiter would add state and a lockout vector for no
protection. What protects the endpoint is rotation plus replay detection: a token works once, and a
second use kills the session. General request-rate limiting remains open (§11).

### Housekeeping

Rows are **revoked, never deleted** by the request paths, because replay detection has to tell "already
rotated" from "never existed". `ExpiredRefreshTokenPurge` therefore deletes rows past
`app.security.session.expired-retention` (default 7 days) beyond their expiry, on
`app.security.session.purge-cron` (default 03:15 daily). Retention exists so a detected replay is still
investigable after the tokens involved have expired.

Only **expiry** decides deletion, never revocation: a revoked but unexpired row must stay findable,
because a replay of it is precisely what must be caught.

### Client guidance

- Store the refresh token where page script cannot read it; it is the long-lived credential.
- **Replace your stored pair on every refresh.** Keeping the old refresh token means presenting a spent
  one next time, and having the session revoked as a suspected replay.
- A 401 from `/api/auth/refresh` means discard both tokens and sign in again — not retry.
- A successful own-password change ends the current session too, so expect one 401 and a fresh login.

## 6. JWT details

### 6.1 Signing

HMAC **HS256**, via Spring Security's Nimbus-backed `JwtEncoder` and `JwtDecoder`. No JWT is
assembled, signed or parsed by hand anywhere in this codebase.

The key is `app.security.jwt.secret`, required to be at least **32 bytes**; startup fails with an
actionable message if a shorter one is supplied. If the property is blank, a random 256-bit key is
generated for that JVM and a loud warning is logged — secure, but tokens do not survive a restart and
will not validate on another instance. There is no insecure default and no committed key.

### 6.2 Claims

| Claim | Value |
|---|---|
| `iss` | `app.security.jwt.issuer` (default `it-software-support-portal`) |
| `sub` | the application user id — the only identity the server trusts |
| `iat`, `exp` | issued-at and expiry, from `app.security.jwt.ttl` |
| `email` | convenience for the client |
| `role` | the user's `Role`, from which authorities are derived per request |
| `tv` | the account's revocation counter at issue time (Phase 7) — see §6.5 |
| `jti` | a unique id per token, so one token is identifiable in logs without logging its value |

Granted authorities are deliberately **not** in the token; see §3.4.

### 6.3 Validation

Three layers, all of which must pass, all inside the `JwtDecoder` — so a rejected token never becomes
an `Authentication` that a controller, a `@PreAuthorize` expression or `CurrentUserProvider` could act
on:

1. **Signature**, against whichever key in the configured set the token's `kid` header names,
   restricted to HS256. Pinning the algorithm matters: honouring whatever the token's own header asks
   for is how `alg: none` and public-key-as-HMAC-secret confusion attacks work.
2. **Standard claims** — expiry, not-before and issuer, via `JwtValidators.createDefaultWithIssuer`.
   Default clock skew applies.
3. **Revocation** — `AccessTokenRevocationValidator`; see §6.5.

A garbage, tampered or foreign-signed token is a 401; there are tests for each.

**Token lifetime**: `app.security.jwt.ttl`, default **30 minutes**.

### 6.5 Revocation — closed in Phase 7

Phases 4 and 5 both recorded the same open limitation: a signed, unexpired token stayed valid no matter
what happened to the account behind it, so **a deactivated user kept read access for the rest of the
token's lifetime**. Phase 7 closes it.

`users.token_version` is a counter. Every issued token carries the value current at issue time in its
`tv` claim, and the validator refuses any token whose claim no longer matches. One increment therefore
invalidates every outstanding token for that user, with no denylist to grow without bound.

A counter rather than a "revoked before" timestamp deliberately: a counter needs no clock agreement
between issuer and verifier, and cannot be defeated by skew or by two revocations landing in the same
clock tick.

**What increments it**

| Event | Endpoint | Also revokes refresh tokens |
|---|---|---|
| Sign out everywhere | `POST /api/auth/logout-all` | yes |
| Own password change | `PATCH /api/users/me/password` | yes |
| Administrative password reset | `POST /api/users/{id}/password-reset` | yes |
| Deactivation | `PATCH /api/users/{id}/status` | yes |
| Role change | `PATCH /api/users/{id}/role` | yes |
| Administrative force sign-out | `DELETE /api/users/{id}/sessions` | yes |

The validator also checks `active` independently of the counter, so an account disabled by a direct
database edit — where nothing bumped the counter — is still refused.

**Tokens issued before Phase 7** carry no `tv` claim and are refused as malformed. That is the only
safe reading of "this token predates revocation support", and it costs at most one re-login.

**The cost, stated honestly.** Checking revocation means consulting authoritative state on requests a
self-contained JWT was meant to answer alone. One query per request would break the property Phase 3
measured and Phase 5 preserved — two SQL statements per ticket page at any size. So
`CachingPrincipalStateRegistry` caches the two-column lookup for `app.security.session.state-cache-ttl`
(default **15s**) and every revoking code path invalidates the entry write-through — **twice: once
immediately, and again once the transaction completes.**

The second invalidation is not belt-and-braces. The new counter value is invisible to other
transactions until the revoking one commits, so a concurrent request arriving between the invalidation
and the commit would re-read the *old* version and cache it again — and the token being revoked would
keep working for up to the cache TTL. The window is narrow, which is precisely what makes it the kind
of defect that survives review and then fails rarely. The hook is `afterCompletion`, not `afterCommit`,
so a rollback clears anything cached mid-transaction too; dropping a cache entry is always safe.

- On a single instance, revocation is **immediate** and the hot path still costs **no SQL**.
- The TTL bounds only changes this instance did not make: a direct database edit, or another instance.
- **Set the TTL to `0` for a multi-instance deployment.** That disables caching, making revocation
  immediate everywhere, at one small indexed select per authenticated request.
- **No claim is made that the default is cluster-wide.** With N instances and a 15-second TTL, a
  revocation on one is honoured by the others within 15 seconds.

### 6.6 Key rotation — added in Phase 7

Previously there was one signing secret, so changing it signed every user out at once. The practical
consequence of that cost is that the secret never gets rotated, which is the worst outcome available.

Tokens now carry a `kid` header and the decoder selects the verification key by it from a set:

1. Put the new secret in `app.security.jwt.secret` with a new `app.security.jwt.key-id`, and move the
   **old** secret and its id into `app.security.jwt.previous-keys[0]`.
2. Restart. New tokens are signed with the new key; tokens already in circulation still verify against
   the old one, so nobody is signed out.
3. Once the access-token TTL has elapsed, **delete the `previous-keys` entry.**

Step 3 is what makes this a rotation rather than an accumulation — a retained key is a key that can
still verify a token, so the overlap is deliberately short. Every key, retained ones included, is
length-checked at startup.

### 6.7 Remaining limitations

- **Cluster-wide revocation latency** at the default cache TTL — see §6.5. Set the TTL to `0` to remove
  it, or replace `PrincipalStateRegistry` with a shared-store implementation.
- **No forced password change on next sign-in.** An administrative reset ends every session and sets a
  password the administrator chose, but nothing compels the user to change it afterwards. That needs a
  flag, a gate on every other endpoint while it is set, and a change endpoint usable without an
  ordinary session — a feature, listed in §11 rather than half-built.
- **No email-based self-service reset.** Recovery goes through an administrator.
- **HS256, symmetric.** Every instance that validates a token can also mint one. Asymmetric signing
  (RS256/ES256) would separate those, and the `kid` machinery added here is the precondition for it.

---

## 7. Secrets and configuration

No secret is committed. Everything is injected, with local-development defaults only where the value
is not sensitive.

| Setting | Property | Environment variable |
|---|---|---|
| Database URL | `spring.datasource.url` | `DB_URL` |
| Database user | `spring.datasource.username` | `DB_USERNAME` |
| Database password | `spring.datasource.password` | `DB_PASSWORD` |
| JWT signing key | `app.security.jwt.secret` | `APP_JWT_SECRET` |
| Active signing key id | `app.security.jwt.key-id` | `APP_JWT_KEY_ID` (default `primary`) |
| Retired signing keys | `app.security.jwt.previous-keys[n].{key-id,secret}` | — (set during a rotation only; §6.6) |
| Token lifetime | `app.security.jwt.ttl` | — (default `30m`) |
| Refresh-token lifetime | `app.security.session.refresh-ttl` | `APP_REFRESH_TTL` (default `7d`) |
| Revocation-check cache TTL | `app.security.session.state-cache-ttl` | `APP_SESSION_STATE_CACHE_TTL` (default `15s`; **set `0` for multi-instance**) |
| Expired-token retention | `app.security.session.expired-retention` | `APP_SESSION_EXPIRED_RETENTION` (default `7d`) |
| Max sessions per user | `app.security.session.max-sessions-per-user` | `APP_MAX_SESSIONS_PER_USER` (default `10`, `0` = no cap) |
| Token purge schedule | `app.security.session.purge-cron` | `APP_SESSION_PURGE_CRON` (default `0 15 3 * * *`) |
| Allowed browser origins | `app.security.cors.allowed-origins` | `APP_CORS_ORIGINS` |
| Swagger public | `app.security.swagger-public` | `APP_SWAGGER_PUBLIC` |
| Bootstrap admin email | `app.security.bootstrap-admin.email` | `APP_BOOTSTRAP_ADMIN_EMAIL` |
| Bootstrap admin password | `app.security.bootstrap-admin.password` | `APP_BOOTSTRAP_ADMIN_PASSWORD` |

`app.security.jwt.secret` is deliberately **empty** in `application.properties`. Committing a signing
key would mean anyone with repository access could mint valid tokens.

> **Pre-existing item, unchanged by Phase 4.** `compose.yaml` still contains literal development
> passwords and starts three database engines whose names do not match `application.properties`. It was
> already unusable as written (audit finding P2-4) and is a deployment concern, not a credential leak
> from this phase.

---

## 8. CORS and CSRF

**CSRF protection is disabled — deliberately, and for a specific reason.** CSRF exists because
browsers attach ambient credentials (cookies) to cross-site requests automatically. This API
authenticates only via an `Authorization: Bearer` header, which a browser never attaches on its own, so
there is no ambient credential to ride. Session creation is `STATELESS` and no cookie is set anywhere;
a test asserts no `Set-Cookie` header is returned. **If cookie authentication is ever introduced, CSRF
protection must come back with it.**

**CORS** is configured in `SecurityConfig.corsConfigurationSource`:

- exact origins from configuration, never a wildcard;
- `allowCredentials` defaults to **false**, since bearer tokens do not need it;
- allowed headers limited to `Authorization`, `Content-Type`, `Accept`;
- **exposed headers** include `X-Total-Count`, `X-Total-Pages`, `X-Page-Number`, `X-Page-Size`,
  `X-Has-Next`. Without this a browser client cannot read the pagination metadata that Phase 3 added —
  the previous `CorsConfig` declared no exposed headers, so those headers were invisible to the SPA.

`OPTIONS` preflight is permitted without authentication; it carries no credentials and must not be
challenged.

### Security headers — reviewed in Phase 5

Observed on a live response (`curl -D -` against `POST /api/auth/login`):

| Header | Value | Status |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Spring Security default, kept explicit. Stops a browser second-guessing our declared content type. |
| `X-Frame-Options` | `DENY` | Default, kept explicit. This is an API; it should never be framed. |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` | Default. Correct and important here: a response containing an access token must not be cached by a browser or an intermediary. Now asserted by a test so a future header change cannot silently remove it. |
| `Pragma` | `no-cache` | Default, HTTP/1.0 companion to the above. |
| `Referrer-Policy` | `no-referrer` | **Added in Phase 5.** Not a Spring Security default. Stops a URL containing a ticket or user id leaking to third parties through the `Referer` header. Safe for both the JSON API and the Swagger UI, neither of which uses referrer information. |

**Deliberately not added:**

- **`Content-Security-Policy`** — this application serves the Swagger UI, which relies on inline scripts
  and styles. A CSP strict enough to be worth having would break it, and one loose enough not to would be
  decoration. The SPA is served separately and should set its own CSP, where it can be matched to the
  assets that page actually loads. Revisit if the Swagger UI is removed or moved behind
  `app.security.swagger-public=false` in all environments.
- **`Strict-Transport-Security`** — Spring Security emits HSTS only over HTTPS, which is already the right
  conditional behaviour. Verified absent over plain HTTP. Forcing it on would be meaningless locally and
  risky before TLS termination is decided: HSTS is hard to retract once a browser has cached it.
- **`X-XSS-Protection`** — deprecated and ignored by current browsers; no value in setting it.

---

## 9. Public versus protected endpoints

**Public:**

| Endpoint | Note |
|---|---|
| `POST /api/auth/login` | Email and password are the credential |
| `POST /api/auth/refresh` | **Not unauthenticated** — the refresh token in the body is the credential, verified against `refresh_tokens` before anything happens. Public because by the time a client refreshes, its access token has expired; that is the point of it |
| `POST /api/auth/logout` | Same: the refresh token is the credential. Always 204, so it is not an oracle for guessed tokens |
| `/error` | Container error dispatch |
| `OPTIONS /**` | CORS preflight |
| `/v3/api-docs/**`, `/swagger-ui/**` | Only when `app.security.swagger-public=true` (default). Set it to `false` in production: the schema of every endpoint is information an attacker does not need |

**Protected** — everything else, by filter-chain default. Current rules:

| Method | Path | Required |
|---|---|---|
| POST | `/api/tickets` | `TICKET_CREATE` |
| GET | `/api/tickets` | `TICKET_READ_OWN` + ownership restriction unless `TICKET_READ_ALL` |
| GET | `/api/tickets/{id}` | `TICKET_READ_OWN` + involvement, or `TICKET_READ_ALL` |
| GET | `/api/tickets/{id}/history` | `TICKET_READ_OWN` + involvement, or `TICKET_READ_ALL` — the same check, applied to the ticket, so the trail cannot be read around it |
| PUT | `/api/tickets/{ticketId}/assign/{userId}` | `TICKET_ASSIGN` |
| PATCH | `/api/tickets/{ticketId}/status` | `TICKET_STATUS_CHANGE` |
| POST | `/api/auth/logout-all` | authenticated — acts on the caller only. Ends every session *and* invalidates their outstanding access tokens, including the one making the call |
| GET | `/api/auth/me` | authenticated — the caller's identity and effective permissions, so a client need not decode the token or reimplement the role mapping. Advisory: the server enforces permissions on every request regardless |
| POST | `/api/users` | `USER_MANAGE` |
| GET | `/api/users` | `USER_READ` |
| GET | `/api/users/{id}` | authenticated, and `USER_READ` or own record |
| PATCH | `/api/users/me/password` | authenticated — acts only on the caller's own account, and verifies the current password. Deliberately no permission: the bootstrap administrator must be able to replace the password it was deployed with. **Ends every session, including the current one** — see below |
| POST | `/api/users/{id}/password-reset` | `USER_MANAGE` — administrative recovery, no current password required. Ends every session the target holds |
| PATCH | `/api/users/{id}/status` | `USER_MANAGE` — activate or deactivate. Cannot target yourself; cannot deactivate the last active administrator. Deactivating ends every session |
| PATCH | `/api/users/{id}/role` | `USER_MANAGE` — cannot target yourself; cannot demote the last active administrator. Ends every session, so new authorities apply at once |
| DELETE | `/api/users/{id}/sessions` | `USER_MANAGE` — force sign-out without changing the password or role |
| GET | `/api/applications`, `/api/applications/{id}`, `/api/applications/active` | `APPLICATION_READ` |
| POST, PUT, DELETE | `/api/applications`, `/api/applications/{id}` | `APPLICATION_MANAGE` |

**Actuator is not on the classpath**, so there are no management endpoints to expose. If it is added
later, its endpoints must be restricted explicitly.

### Actor versus target

`PUT /api/tickets/{ticketId}/assign/{userId}` carries a user id in the path. That is the assignment
**target**, never the actor. The acting user always comes from the authenticated principal; a client
cannot nominate who performed an action. The audit trail records the actor, with the target as the
changed value:

| Action | `changed_by` (actor) | `new_value` (target) |
|---|---|---|
| `CREATED` | the raiser | `OPEN` |
| `ASSIGNED` | the user who performed the assignment | the assignee's name |
| `STATUS_CHANGED` | the user who changed the status | the new status |

Phase 2 corrected `changed_by` on assignment rows from the *assignee* to the *actor*. That correction
must not regress; there is a test for it.

---

## 10. Security testing

Security tests live in `src/test/java/.../security/` and are described in `docs/TESTING.md`.
Categories:

| Category | Where | Covers |
|---|---|---|
| Authentication | `AuthenticationSecurityTest` (13 tests) | correct and incorrect credentials, no-password accounts, deactivated accounts, garbage/tampered/foreign-signed tokens, 401 shape, `WWW-Authenticate`, no session cookie, login validation, no credential in the response |
| Authorization | `AuthorizationSecurityTest` (10 tests) | every role against every capability, 403 shape, denial is not a 500, unmapped role grants nothing |
| IDOR / BOLA | `ResourceAccessControlTest` (14 tests) | cross-user ticket and user access, restricted list and count, filter cannot widen, assignee and raiser visibility, unassigned-ticket visibility, no hash in responses |
| Identity spoofing | `TicketIdentityRegressionTest` | `X-User-Id` cannot override or supply identity; no fallback to user 1 |
| **Session lifecycle** | `SessionLifecycleSecurityTest` (30 tests) | login returns both tokens; only a hash is stored; rotation; spent and unknown tokens refused; replay revokes the family; families are independent; logout idempotent and not an oracle; logout-all kills a live access token; `/me` shape, permissions and session count; token responses not cacheable |
| **Revocation and admin controls** | `TokenRevocationSecurityTest` (26 tests) | deactivation, role change, own password change, administrative reset and forced sign-out each refuse an existing access token on the *next* request; self-targeting and last-administrator guards; `USER_MANAGE` on every admin endpoint; 404 and 400 shapes; revocation scoped to one account |
| Rotation and expiry rules | `unit/RefreshTokenRotationTest` (30 tests, no Docker) | hash-only storage, per-login families, rotation, replay, concurrency loser, expiry boundary, deactivated and passwordless accounts, logout idempotence, version bump, session cap |
| Revocation verdicts and cache | `unit/AccessTokenRevocationTest` (19 tests, no Docker) | superseded version, deactivated, deleted, missing/unparseable `tv`, unusable subject, identical failure text; cache TTL, write-through invalidation, zero-TTL read-through, negative caching, bounding |
| Key rotation and token crypto | `unit/JwtKeyRotationTest` (15 tests, no Docker) | key set, retained keys, short/duplicate/incomplete keys refused, hash shape and determinism, 256-bit opaque tokens, 10,000 distinct |
| Admin guards | `unit/AccountAdministrationGuardsTest` (17 tests, no Docker) | self-deactivation, self-demotion, last-active-administrator, no-op changes, which operations revoke and which do not |
| Password handling | `AuthenticationSecurityTest`, `ResourceAccessControlTest` | hashing, uniform failure, nothing exposed |
| Audit actor | `TicketStatusIntegrityRegressionTest` | the actor is the authenticated caller, not the assignee |
| 401 / 403 mapping | `GlobalExceptionHandlerTest` (unit, no Docker) | status and body for each exception type |
| Security regression | the suite as a whole | all Phase 2 integrity and Phase 3 performance guarantees still hold with security enabled |

---

## 11. Known security follow-ups

**Future enhancements** — candidates for a later phase. None of these is a live vulnerability in the
current design; each is a known limit, recorded so it is a decision rather than an oversight.

1. ~~**Token revocation strategy.**~~ **Done in Phase 7** — see §6.5. A `tv` claim is compared against
   `users.token_version` inside the decoder, so one increment ends every outstanding token for an
   account. A denylist of token ids was considered and rejected: it grows without bound and needs its
   own eviction policy, where a counter needs neither. One piece of this area is still open —
   **cluster-wide revocation latency** at the default cache TTL; set
   `app.security.session.state-cache-ttl=0` to remove it, or replace `PrincipalStateRegistry` with a
   shared-store implementation.
2. ~~**Refresh tokens.**~~ **Done in Phase 7** — see §5b. Opaque, hashed at rest, rotated on every use,
   with family-wide revocation on replay.
3. ~~**Signing-key rotation.**~~ **Done in Phase 7** — see §6.6. Tokens carry a `kid` and the decoder
   selects from a key set, so a secret can be retired after a short overlap instead of signing everyone
   out. The same machinery is the precondition for moving to asymmetric signing, which remains open.
4. **External identity provider integration.** The seam already exists (§1.4). Note that a provider's
   own revocation semantics would replace §6.5's third validation layer.
4a. **Forced password change after an administrative reset**, and **email-based self-service reset** —
   see §6.7. Recovery currently goes through an administrator, who chooses the replacement password.
5. ~~**Rate limiting and brute-force protection.**~~ **Done in Phase 5** — see §5a. Two remaining pieces
   of this area are still open: a **distributed limiter** so throttling is cluster-wide rather than
   per-instance (§5a, "Single-instance limitation"), and **general request-rate limiting** beyond login —
   nothing currently throttles an authenticated client flooding the API, which is a capacity concern
   rather than a credential one.
6. ~~**Security header review.**~~ **Done in Phase 5** — see §8, "Security headers". `Referrer-Policy`
   was added; CSP and HSTS were reviewed and deliberately not set, with reasons recorded. CSP becomes
   worth revisiting if the Swagger UI stops being served from this application.
7. ~~**Further API pagination.**~~ **Done in Phase 6** — `GET /api/users`, `GET /api/applications` and
   `GET /api/applications/active` are now bounded, with a hard page-size cap of 100 and a whitelist of
   sortable properties. Two security-relevant details: the sort whitelist is what stops a caller naming
   `passwordHash` as an order key, and the user listing reads through a projection that never selects
   `password_hash` at all, so a directory page cannot leak a hash through a logger, a debugger or a
   future DTO field. Asserted against the SQL text in `UserListBaselineTest`. Measurements:
   docs/PERFORMANCE.md §15.
8. **Organisation / tenant authorization.** See below.

### Tenant context — where it would go

Multi-tenancy is **not** implemented, and no `tenant_id` column was added anywhere. If it is ever
required:

- **Entry point**: the principal. A tenant claim would be read in `JwtRoleAuthoritiesConverter` and
  exposed through the same `CurrentUserProvider` seam, so services would obtain tenant context exactly
  as they obtain user identity today.
- **Resources that would become tenant-scoped**: `tickets`, `users`, `applications`,
  `ticket_comments`, `ticket_attachments`, `ticket_history_tracking`.
- **Likely database strategy**: a nullable `organization_id` discriminator with a Hibernate filter, in
  preference to schema- or database-per-tenant. It is a far smaller change, it is reversible, and the
  ownership predicate in `TicketQueryRepositoryImpl` is already the natural place for a tenant
  predicate to join it.

The precursor is real `Department` and `Team` tables — `users.department_id` currently references
nothing — which is also what blocks the authorization question in §12.

---

## 12. Pending business decisions

These are **business decisions, not technical limitations**, and none has been decided. Each is
recorded in a test that documents the current behaviour without endorsing it.

1. **Which ticket status transitions are legal.** The only statement of intent anywhere is one
   happy-path comment in `TicketServiceImpl`; `UNDER_REVIEW`, `PENDING` and `REOPENED` appear in no
   production logic. Any transition is currently accepted.
2. **Whether reassignment resets status.** `assignTicket` forces `ASSIGNED`, so reassigning an
   `IN_PROGRESS` ticket moves it backwards.
3. **Department visibility for `MANAGER`, `HOD` and `DIRECTOR`.** Blocked on departments not existing
   (§3.3). Until it is decided, these roles have requester-level permissions only.

A related question surfaced by Phase 4: **may a requester change the status of their own ticket** — to
close it once satisfied, for instance? The permission is currently withheld, which is the safe
direction, but it is a business decision rather than a security conclusion.
