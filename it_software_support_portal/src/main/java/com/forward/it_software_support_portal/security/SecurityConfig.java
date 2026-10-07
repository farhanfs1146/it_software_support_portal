package com.forward.it_software_support_portal.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.forward.it_software_support_portal.security.session.AccessTokenRevocationValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security filter chain, authority wiring and crypto primitives.
 *
 * <p><strong>Model: stateless JWT bearer tokens.</strong> Chosen over server-side sessions because the
 * API is consumed by a browser SPA and the application is meant to scale horizontally. Sessions would
 * need either sticky routing or a shared session store, and a shared store is explicitly out of scope.
 * Stateless tokens also keep the door open for an external identity provider: that becomes a change to
 * {@link #jwtDecoder} plus retiring the login endpoint, with no effect on business services. The
 * trade-off is that a token cannot be revoked before it expires - see docs/SECURITY.md.
 *
 * <p><strong>CSRF is disabled, deliberately and for a specific reason.</strong> CSRF protection exists
 * because browsers attach ambient credentials - cookies - to cross-site requests automatically. This
 * API authenticates only via an {@code Authorization: Bearer} header, which a browser never attaches on
 * its own, so there is no ambient credential for an attacker to ride. No cookie or session is created
 * anywhere. Were cookie authentication ever introduced, CSRF protection would have to come back with
 * it.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityConfig {


    static final MacAlgorithm JWS_ALGORITHM = MacAlgorithm.HS256;

    /**
     * The only paths reachable without a valid access token.
     *
     * <p>{@code /api/auth/refresh} and {@code /api/auth/logout} are here because an access token is
     * precisely what the caller does not have in either case - the whole point of refresh is that the
     * access token has expired. They are not unauthenticated: the refresh token in the request body is
     * the credential, and it is verified against {@code refresh_tokens} before anything happens. Both
     * are safe to expose because an unknown token does nothing at all, and a 256-bit random value is
     * not guessable.
     *
     * <p>{@code /api/auth/logout-all} and {@code /api/auth/me} are deliberately <em>not</em> here: both
     * act on "the current user", which only an access token can establish.
     */
    private static final String[] ALWAYS_PUBLIC = {
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/error"
    };

    private static final String[] SWAGGER_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtRoleAuthoritiesConverter authoritiesConverter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // See the class comment: bearer-only API, no ambient credentials, no sessions.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(ALWAYS_PUBLIC).permitAll();
                    if (properties.swaggerPublic()) {
                        requests.requestMatchers(SWAGGER_PATHS).permitAll();
                    }
                    // CORS preflight carries no credentials and must not be challenged.
                    requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    // Default deny: any endpoint added later is protected until someone says otherwise.
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authoritiesConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // Header policy. Spring Security's defaults already cover the ones that matter most for
                // an API, so this block adds one header and otherwise leaves them alone - see the
                // "Security headers" section of docs/SECURITY.md for what was reviewed and rejected.
                .headers(headers -> headers
                        // Already a default; kept explicit because this is an API that should never be
                        // framed.
                        .frameOptions(frame -> frame.deny())
                        // Already a default; stops a browser second-guessing our declared content type.
                        .contentTypeOptions(options -> {
                        })
                        // Added in Phase 5. Not a Spring Security default. Cheap, and it stops a URL
                        // containing a ticket or user id leaking to third parties through the Referer
                        // header. Safe for both the JSON API and the Swagger UI, neither of which
                        // depends on referrer information.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));

        return http.build();
    }

    /**
     * BCrypt, through Spring Security's own encoder.
     *
     * <p>No custom cryptography, no reversible encryption, and no plaintext. BCrypt is deliberately
     * slow, which is the property that matters for a password verifier.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The keys tokens may be signed with and verified against, shared so the encoder and decoder
     * cannot drift apart.
     *
     * <p>A <em>set</em> rather than a single key since Phase 7, so the signing secret can be rotated
     * without invalidating every token already in circulation. {@link JwtKeys} carries the procedure
     * and the reasoning. The behaviour when nothing is configured is unchanged: a strong ephemeral key
     * for this JVM, with a loud warning.
     */
    @Bean
    public JwtKeys jwtKeys() {
        return JwtKeys.from(properties.jwt());
    }

    /**
     * Signs with the active key. {@code JwtTokenService} sets the matching {@code kid} header, which is
     * what lets the decoder below pick the right key out of the set.
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtKeys jwtKeys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(jwtKeys.jwkSet()));
    }

    /**
     * Validates a presented access token in three layers, all of which must pass.
     *
     * <ol>
     *   <li><strong>Signature</strong>, against whichever key in the set the token's {@code kid} names,
     *       restricted to {@link #JWS_ALGORITHM}. Pinning the algorithm matters: accepting whatever the
     *       token's own header asks for is exactly how {@code alg: none} and
     *       public-key-as-HMAC-secret confusion attacks work.</li>
     *   <li><strong>Standard claims</strong> - expiry, not-before and issuer - via Spring's defaults.</li>
     *   <li><strong>Revocation</strong> - the account still exists, is still active, and the token's
     *       version claim still matches. This is the Phase 7 addition; see
     *       {@link com.forward.it_software_support_portal.security.session.AccessTokenRevocationValidator}.</li>
     * </ol>
     *
     * <p>All three run inside the decoder, so a revoked token never becomes an {@code Authentication}
     * that a controller, a {@code @PreAuthorize} expression or {@code CurrentUserProvider} could act on.
     *
     * <p>This remains the single seam an external identity provider would replace: point the decoder at
     * the provider's JWKS endpoint and nothing else in the application changes - though a provider's
     * own revocation semantics would then replace layer 3.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtKeys jwtKeys, AccessTokenRevocationValidator revocationValidator) {
        JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(
                JWSAlgorithm.parse(JWS_ALGORITHM.getName()),
                new ImmutableJWKSet<>(jwtKeys.jwkSet()));

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        // Claim checking belongs to the Spring validators below. Leaving Nimbus's default claims
        // verifier in place would mean two layers enforcing overlapping rules with different error
        // reporting, and only one of them producing the RFC 7807 response this API guarantees.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                revocationValidator));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Exact origins only. A wildcard origin combined with credentials is rejected by browsers and
        // is a misconfiguration regardless.
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Without this a browser client cannot read the pagination metadata added in Phase 3.
        configuration.setExposedHeaders(List.of(
                "X-Total-Count", "X-Total-Pages", "X-Page-Number", "X-Page-Size", "X-Has-Next"));
        configuration.setAllowCredentials(properties.cors().allowCredentials());
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
