package com.forward.it_software_support_portal.support;

import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.JwtTokenService;
import com.forward.it_software_support_portal.security.ratelimit.InMemoryLoginAttemptLimiter;
import com.forward.it_software_support_portal.security.ratelimit.LoginAttemptLimiter;
import com.forward.it_software_support_portal.security.session.CachingPrincipalStateRegistry;
import com.forward.it_software_support_portal.security.session.PrincipalStateRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

/**
 * Base class for every database-backed test.
 *
 * <p>Provides a single PostgreSQL container shared by the whole test JVM. The container is created
 * as a static field (construction does not contact Docker) and started lazily from
 * {@link #datasourceProperties}, which only runs when a test class has actually been enabled - so a
 * machine without Docker skips cleanly instead of erroring during class initialisation.
 *
 * <p>Because every subclass declares the same {@code @SpringBootTest} properties, Spring's test
 * context cache gives the entire suite one application context and therefore one container, rather
 * than one per test class.
 *
 * <p>The datasource is pointed at the container, overriding {@code application.properties}. No test
 * ever touches a developer's local database, and no local credentials appear anywhere in test code.
 *
 * <p>Flyway is deliberately left to run normally: the schema is created by the real migrations in
 * {@code db/migration}, never by {@code ddl-auto} or hand-written DDL.
 *
 * <p>Concrete subclasses must be annotated with {@link DatabaseIntegrationTest}.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // lets tests count SQL statements without parsing logs (used by the performance baseline)
                "spring.jpa.properties.hibernate.generate_statistics=true",
                // lets tests assert on the SQL text itself; inert until a test starts recording
                "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                        + "com.forward.it_software_support_portal.support.CapturingStatementInspector",
                "spring.jpa.show-sql=false"
        }
)
public abstract class AbstractIntegrationTest {

    /**
     * Pinned image tag so the suite is reproducible. Matches the PostgreSQL major version used in
     * development (18).
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Every application table, child-first. Listed explicitly rather than discovered so that a new
     * table added in a later phase fails loudly here instead of silently leaking state between tests.
     */
    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE
                refresh_tokens,
                ticket_history_tracking,
                ticket_history,
                ticket_attachments,
                ticket_comments,
                tickets,
                applications,
                users
            RESTART IDENTITY CASCADE
            """;

    /**
     * Resets to a clean, reproducible state before each test.
     *
     * <p>Truncation is used rather than a rolled-back {@code @Transactional} test, because several
     * tests in this suite need to observe what the application actually committed. Wrapping tests in
     * a transaction would hide exactly the commit behaviour the atomicity tests exist to prove.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.execute(TRUNCATE_ALL);
        // Keep ticket numbers predictable per test. The sequence is non-transactional, so it would
        // otherwise carry over between tests and make asserted numbers order-dependent.
        jdbc.execute("ALTER SEQUENCE ticket_number_seq RESTART WITH 1");
    }

    /**
     * Clears login-throttle state so test methods stay independent.
     *
     * <p>The limiter is a singleton for the whole Spring context, so failed logins in one test would
     * otherwise count towards the next one's budget. Clearing is test isolation, not a weakening of the
     * protection - {@code LoginRateLimitSecurityTest} exercises the real thresholds within a single test.
     */
    @BeforeEach
    void resetLoginThrottle() {
        if (loginAttemptLimiter instanceof InMemoryLoginAttemptLimiter inMemory) {
            inMemory.clearAll();
        }
    }

    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    /**
     * Clears the access-token revocation cache between tests.
     *
     * <p>Required, not hygiene. Every test truncates {@code users} and inserts fresh rows, so ids are
     * reused across tests while the cache is keyed on id. Without this, a test that deactivated user 1
     * would leave "user 1 is inactive" cached, and the next test's brand-new user 1 would be refused.
     */
    @BeforeEach
    void resetPrincipalStateCache() {
        if (principalStateRegistry instanceof CachingPrincipalStateRegistry caching) {
            caching.clearAll();
        }
    }

    @Autowired
    protected PrincipalStateRegistry principalStateRegistry;

    /**
     * The default request factory rejects PATCH, which {@code PATCH /api/tickets/{id}/status} needs.
     * {@link JdkClientHttpRequestFactory} is built on {@code java.net.http.HttpClient} and supports
     * it, so no extra HTTP client dependency is required.
     */
    @BeforeEach
    void enablePatchSupport() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    // ---------------------------------------------------------------- fixtures

    /** Inserts a user directly, bypassing the API, and returns its generated id. */
    protected long insertUser(String fullName, String email, long employeeCode, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (employee_code, full_name, email, role, active)
                VALUES (?, ?, ?, ?, true)
                RETURNING id
                """, Long.class, employeeCode, fullName, email, role);
    }

    protected long insertUser(String fullName) {
        long code = Math.abs(System.nanoTime() % 1_000_000_000L);
        return insertUser(fullName, "user" + code + "@example.test", code, "EMPLOYEE");
    }

    protected long insertApplication(String appName, String moduleName) {
        return jdbc.queryForObject("""
                INSERT INTO applications (app_name, module_name, active)
                VALUES (?, ?, true)
                RETURNING id
                """, Long.class, appName, moduleName);
    }

    /** Inserts a user who can authenticate, with the given plaintext password stored as a hash. */
    protected long insertUserWithPassword(String fullName, String email, long employeeCode,
                                          String role, String password) {
        return jdbc.queryForObject("""
                INSERT INTO users (employee_code, full_name, email, role, active, password_hash)
                VALUES (?, ?, ?, ?, true, ?)
                RETURNING id
                """, Long.class, employeeCode, fullName, email, role,
                passwordEncoder.encode(password));
    }

    // ------------------------------------------------------------- credentials

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * Mints a real, signed access token for an existing user.
     *
     * <p>Deliberately uses the production {@link JwtTokenService} rather than a mock principal, so the
     * tests exercise the same signing, claims and authority derivation the application uses. A test
     * that passed against a hand-built principal could still fail against a real token.
     */
    protected String tokenFor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot mint a token for user " + userId + ": no such user"));
        return jwtTokenService.issue(user).token();
    }

    // ------------------------------------------------------- query accounting

    protected Statistics hibernateStatistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Number of JDBC statements Hibernate has prepared so far in this context. */
    protected long preparedStatementCount() {
        return hibernateStatistics().getPrepareStatementCount();
    }

    /**
     * Runs {@code work} and returns the SQL Hibernate sent while it ran.
     *
     * <p>For assertions about <em>what</em> a query selects, where a statement count cannot help - for
     * instance that a user listing never reads {@code password_hash}. See
     * {@link CapturingStatementInspector}.
     */
    protected List<String> capturingSql(Runnable work) {
        CapturingStatementInspector.start();
        try {
            work.run();
        } catch (RuntimeException failure) {
            CapturingStatementInspector.stop(); // do not leave recording on for the rest of the suite
            throw failure;
        }
        return CapturingStatementInspector.stop();
    }

    // ------------------------------------------------------------ http helpers

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> ARRAY =
            new ParameterizedTypeReference<>() {
            };

    /**
     * Builds headers, optionally carrying a bearer token for the given user.
     *
     * <p>Phase 4 replaced the transitional {@code X-User-Id} header with real authentication, so the
     * {@code ...As(userId, ...)} helpers now attach {@code Authorization: Bearer <token>}. The
     * signatures are unchanged, which is the point: the tests describe <em>who is acting</em>, and the
     * mechanism that proves it changed underneath them.
     *
     * <p>Passing {@code null} for the actor sends no credentials at all - that is how the 401 paths are
     * tested.
     */
    private HttpEntity<Object> entity(Object body, Long actorUserId) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (actorUserId != null) {
            headers.setBearerAuth(tokenFor(actorUserId));
        }
        return new HttpEntity<>(body, headers);
    }

    /** Headers carrying an arbitrary raw token, for testing tampered or forged credentials. */
    private static HttpEntity<Object> entityWithRawToken(Object body, String rawToken) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (rawToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken);
        }
        return new HttpEntity<>(body, headers);
    }

    // --- unauthenticated variants: used to assert that protection exists ---

    protected ResponseEntity<Map<String, Object>> getObject(String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, null), OBJECT);
    }

    protected ResponseEntity<List<Map<String, Object>>> getArray(String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, null), ARRAY);
    }

    protected ResponseEntity<Map<String, Object>> postObject(String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, entity(body, null), OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> putObject(String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.PUT, entity(body, null), OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> patchObject(String path) {
        return rest.exchange(path, HttpMethod.PATCH, entity(null, null), OBJECT);
    }

    protected ResponseEntity<String> getRaw(String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, null), String.class);
    }

    // --- authenticated variants ---

    protected ResponseEntity<Map<String, Object>> getObjectAs(Long actorUserId, String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, actorUserId), OBJECT);
    }

    protected ResponseEntity<List<Map<String, Object>>> getArrayAs(Long actorUserId, String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, actorUserId), ARRAY);
    }

    protected ResponseEntity<String> getRawAs(Long actorUserId, String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null, actorUserId), String.class);
    }

    protected ResponseEntity<Map<String, Object>> postObjectAs(Long actorUserId, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, entity(body, actorUserId), OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> putObjectAs(Long actorUserId, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.PUT, entity(body, actorUserId), OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> patchObjectAs(Long actorUserId, String path) {
        return rest.exchange(path, HttpMethod.PATCH, entity(null, actorUserId), OBJECT);
    }

    /**
     * PATCH with a request body, returning no body.
     *
     * <p>Added for {@code PATCH /api/users/me/password}, the first endpoint here that both takes a
     * body and answers 204 - the existing {@code patchObjectAs} sends no body because
     * {@code /status} carries its argument as a query parameter.
     */
    protected ResponseEntity<Void> patchAs(Long actorUserId, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.PATCH, entity(body, actorUserId), Void.class);
    }

    protected ResponseEntity<Void> deleteAs(Long actorUserId, String path) {
        return rest.exchange(path, HttpMethod.DELETE, entity(null, actorUserId), Void.class);
    }

    protected ResponseEntity<Void> delete(String path) {
        return rest.exchange(path, HttpMethod.DELETE, entity(null, null), Void.class);
    }

    /**
     * Runs an action with the given user authenticated, then restores the previous context.
     *
     * <p>Builds the authentication from a real signed token decoded by the production
     * {@code JwtDecoder} and converted by the production authorities converter, so a service-level test
     * sees exactly the principal and authorities an HTTP request would produce. A hand-built mock
     * principal could pass here and still fail in production.
     */
    protected <T> T asAuthenticated(Long userId, java.util.function.Supplier<T> action) {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            var jwt = jwtDecoder.decode(tokenFor(userId));
            var authentication = authoritiesConverter.convert(jwt);
            var context = org.springframework.security.core.context.SecurityContextHolder
                    .createEmptyContext();
            context.setAuthentication(authentication);
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    @Autowired
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Autowired
    private com.forward.it_software_support_portal.security.JwtRoleAuthoritiesConverter authoritiesConverter;

    /** Sends a caller-supplied token string verbatim, for forged/tampered-credential tests. */
    protected ResponseEntity<Map<String, Object>> getObjectWithToken(String rawToken, String path) {
        return rest.exchange(path, HttpMethod.GET, entityWithRawToken(null, rawToken), OBJECT);
    }

    protected ResponseEntity<Map<String, Object>> postObjectWithToken(
            String rawToken, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, entityWithRawToken(body, rawToken), OBJECT);
    }

    protected ResponseEntity<Void> postWithToken(String rawToken, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, entityWithRawToken(body, rawToken), Void.class);
    }

    protected ResponseEntity<Void> postAs(Long actorUserId, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, entity(body, actorUserId), Void.class);
    }

    protected ResponseEntity<Map<String, Object>> patchObjectAs(
            Long actorUserId, String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.PATCH, entity(body, actorUserId), OBJECT);
    }


    /**
     * Signs in over real HTTP and returns the whole token pair.
     *
     * <p>Used rather than {@code tokenFor(...)} wherever the test is about the session itself: only a
     * real login creates the {@code refresh_tokens} row that refresh, logout and rotation act on.
     * {@code tokenFor} mints an access token directly and deliberately creates no session.
     */
    protected Map<String, Object> login(String email, String password) {
        ResponseEntity<Map<String, Object>> response = postObject("/api/auth/login",
                Map.of("email", email, "password", password));
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Login failed for " + email + ": HTTP " + response.getStatusCode());
        }
        return response.getBody();
    }

    protected String accessTokenOf(Map<String, Object> loginResponse) {
        return (String) loginResponse.get("accessToken");
    }

    protected String refreshTokenOf(Map<String, Object> loginResponse) {
        return (String) loginResponse.get("refreshToken");
    }

    /** Exchanges a refresh token, returning the raw response so a test can assert on failures too. */
    protected ResponseEntity<Map<String, Object>> refresh(String refreshToken) {
        return postObject("/api/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    protected int liveSessionRows(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    protected int tokenVersionOf(long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT token_version FROM users WHERE id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    protected List<String> revocationReasonsFor(long userId) {
        return jdbc.queryForList(
                "SELECT revoked_reason FROM refresh_tokens WHERE user_id = ? AND revoked_reason "
                        + "IS NOT NULL ORDER BY id", String.class, userId);
    }

    /** Sends arbitrary extra headers, for proving a legacy header cannot influence identity. */
    protected ResponseEntity<Map<String, Object>> postObjectAsWithExtraHeader(
            Long actorUserId, String path, Map<String, ?> body, String headerName, String headerValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (actorUserId != null) {
            headers.setBearerAuth(tokenFor(actorUserId));
        }
        headers.set(headerName, headerValue);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), OBJECT);
    }

    // ------------------------------------------------------------- assertions

    protected int countRows(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
