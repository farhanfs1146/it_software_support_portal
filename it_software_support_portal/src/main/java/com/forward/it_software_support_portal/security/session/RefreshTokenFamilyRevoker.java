package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Revokes a rotation family in its own transaction, so the revocation survives the failure that
 * triggered it.
 *
 * <h2>Why this class has to exist</h2>
 *
 * This was a real bug, found by {@code SessionLifecycleSecurityTest} rather than reasoned about in
 * advance, and it is worth recording because the shape of it is easy to repeat.
 *
 * <p>Reuse detection does two things at once: it <strong>refuses</strong> the request, by throwing, and
 * it <strong>revokes</strong> the family, by writing. Those two requirements are in direct conflict
 * under ordinary transaction semantics, because the thrown {@code RuntimeException} rolls the
 * transaction back - and the write is inside it. The first version of this code revoked the family and
 * then threw, which looked right, read right, and rolled the revocation straight back. A replayed
 * token produced a 401 and left the thief's successor token perfectly valid: the detection logged a
 * warning and achieved nothing.
 *
 * <p>{@code REQUIRES_NEW} resolves it. The revocation commits in its own transaction before the
 * exception is thrown, so refusing the request cannot undo the security response to it.
 *
 * <p><strong>A separate bean, not a method on the service.</strong> Spring applies
 * {@code @Transactional} through a proxy, so a service calling its own annotated method bypasses the
 * advice entirely and would silently keep the original bug - the annotation would be there, doing
 * nothing. Crossing a bean boundary is what makes the propagation take effect.
 *
 * <p><strong>Callers must hold no locks on the rows involved.</strong> A new transaction cannot see,
 * or wait out, uncommitted changes made by the suspended one: if the caller had already updated a row
 * in this family, this call would block on that row until the lock timeout. {@code rotate} is ordered
 * so that every path reaching here has written nothing - account checks happen before the presented
 * token is spent, and a reuse is detected precisely by an update that matched no rows.
 */
@Component
public class RefreshTokenFamilyRevoker {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenFamilyRevoker.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** @return how many live tokens were revoked */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyDurably(String familyId, RevocationReason reason, LocalDateTime now) {
        int revoked = refreshTokenRepository.revokeFamily(familyId, reason.name(), now);
        log.debug("Revoked {} token(s) in family {} ({}) in a separate transaction",
                revoked, familyId, reason);
        return revoked;
    }
}
