package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The refresh hot path: one indexed lookup on the unique {@code token_hash}. Returns revoked and
     * expired rows too, on purpose - reuse detection has to be able to tell "this token was already
     * rotated" from "this token never existed", and only a row that still exists can say so.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes one specific token, but only if it is still live, and reports whether it did.
     *
     * <p><strong>This conditional update is how a concurrent refresh is resolved, and it has to be a
     * single statement.</strong> Two requests presenting the same token both reach here; the database
     * serialises them on the row, so exactly one sees a row count of 1 and the other sees 0. The loser
     * is a token being spent twice, which is the replay signal. Loading the row, deciding in Java and
     * then writing would let both racers believe they won, and a genuinely stolen token would rotate
     * happily alongside the real client's.
     *
     * @return 1 if this call revoked the token, 0 if it had already been revoked by someone else
     */
    @Modifying
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.id = :id
               and t.revokedAt is null
            """)
    int revokeIfLive(@Param("id") Long id,
                     @Param("reason") String reason,
                     @Param("now") LocalDateTime now);

    /**
     * Revokes every live token in one rotation chain. Used on logout and on reuse detection: revoking
     * only the token presented would leave its successors - including the one a thief obtained by
     * rotating a stolen token - perfectly valid, so the detection would achieve nothing.
     *
     * <p>A bulk {@code update} rather than load-and-save because the rows are not otherwise needed.
     */
    @Modifying
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.familyId = :familyId
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("reason") String reason,
                     @Param("now") LocalDateTime now);

    /**
     * A user's live tokens, oldest first. Used only to enforce the per-user session cap, so it is
     * ordered rather than paged: the cap is a small number and the partial index from V15 covers the
     * predicate.
     */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullOrderByIssuedAtAscIdAsc(Long userId);

    /**
     * Revokes every live token for a user: logout-everywhere, password change, password reset,
     * deactivation, role change, administrative force-signout.
     */
    @Modifying
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokedReason = :reason
             where t.userId = :userId
               and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("reason") String reason,
                         @Param("now") LocalDateTime now);

    /** How many sessions a user currently holds. Served by the partial index from V15. */
    @Query("select count(t.id) from RefreshToken t where t.userId = :userId and t.revokedAt is null")
    long countLiveForUser(@Param("userId") Long userId);

    /**
     * Deletes rows that are past their expiry by at least the retention window. Retention exists so a
     * detected replay can still be investigated after the tokens involved have expired; without it the
     * evidence would be deleted by the next purge.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :deleteBefore")
    int deleteExpiredBefore(@Param("deleteBefore") LocalDateTime deleteBefore);
}
