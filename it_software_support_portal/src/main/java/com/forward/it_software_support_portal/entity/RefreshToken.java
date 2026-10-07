package com.forward.it_software_support_portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One refresh-token credential: a long-lived, revocable handle that can be exchanged for a short-lived
 * access token.
 *
 * <p><strong>The plaintext token is not here.</strong> {@link #tokenHash} holds a hex-encoded SHA-256
 * digest of the value handed to the client; the client's copy is the only copy. See
 * {@code V15__create_refresh_tokens_and_token_version.sql} for why SHA-256 rather than BCrypt.
 *
 * <p><strong>{@code userId} is a plain column, not a mapped {@code @ManyToOne}.</strong> That is
 * deliberate rather than an oversight. This is authentication infrastructure, never traversed as an
 * object graph: the only access patterns are "find by hash" and bulk revocation by user or by family.
 * A mapped association would add a lazy proxy and a second select on the refresh path, and would pull
 * the {@code User} entity - {@code password_hash} included - into the persistence context every time a
 * token row is touched. Referential integrity is enforced by the foreign key in the database, which is
 * where it belongs.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Groups one rotation chain, so reuse detection can revoke every descendant of a leaked token. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Non-null once this token can no longer be exchanged. Rows are revoked, never silently deleted. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 40)
    private String revokedReason;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** A token is usable only if it has been neither revoked nor expired. */
    public boolean isUsableAt(LocalDateTime now) {
        return !isRevoked() && !isExpiredAt(now);
    }
}
