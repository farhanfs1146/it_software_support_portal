package com.forward.it_software_support_portal.security.ratelimit;

import com.forward.it_software_support_portal.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Determines the address an attempt came from, for rate-limiting purposes.
 *
 * <h2>Why this is not a one-liner</h2>
 *
 * The obvious implementation — read {@code X-Forwarded-For} — is a rate-limiter bypass. That header is
 * just a request header: any client can send it, with any value, including a different value on every
 * request. Trusting it unconditionally means an attacker defeats per-address throttling completely by
 * varying one string, and worse, can pin the blame on someone else's address and have <em>them</em>
 * throttled.
 *
 * <h2>Current behaviour: the real socket address</h2>
 *
 * By default {@code app.security.rate-limit.trust-forwarded-headers} is <strong>false</strong> and this
 * returns {@link HttpServletRequest#getRemoteAddr()} — the peer of the TCP connection, which a client
 * cannot forge without actually controlling that address.
 *
 * <p><strong>Deployment assumption.</strong> This is correct for the deployment this application
 * currently supports: clients connect to it directly. Nothing in {@code application.properties} or
 * {@code compose.yaml} configures a reverse proxy, and {@code server.forward-headers-strategy} is not
 * set, so there is no trusted hop whose header could be believed. If the application is later placed
 * behind a load balancer or ingress <em>without</em> enabling the flag below, every request will appear
 * to come from the proxy's address and the per-address limit will effectively become a global one — the
 * per-account limit still protects individual passwords, but the address dimension stops distinguishing
 * callers. That is a safe failure direction (over-throttling, not under-throttling), and it is the
 * reason this is documented rather than silently guessed at.
 *
 * <h2>Enabling forwarded headers</h2>
 *
 * Set the flag only when every request genuinely passes through a proxy that <em>appends</em> the real
 * peer to {@code X-Forwarded-For}. This then reads the <strong>right-most</strong> entry, not the
 * left-most. That detail is the whole point: a client can prefill the header with any number of fake
 * entries, and the proxy appends the only trustworthy value at the end. Taking the left-most entry — the
 * common mistake — reads attacker-controlled data.
 */
@Component
public class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final SecurityProperties.RateLimit config;

    public ClientIpResolver(SecurityProperties properties) {
        this.config = properties.rateLimit();
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        if (config.trustForwardedHeaders()) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                // Right-most: the entry our trusted proxy appended. Everything to its left may be
                // client-supplied fiction.
                String candidate = hops[hops.length - 1].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }

        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
