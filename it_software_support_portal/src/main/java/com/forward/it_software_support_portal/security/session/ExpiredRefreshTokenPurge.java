package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.repository.RefreshTokenRepository;
import com.forward.it_software_support_portal.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Deletes refresh-token rows that are long past their expiry.
 *
 * <h2>Why this job has to exist</h2>
 *
 * Rows are revoked, never deleted, by every other code path in this package - that is what lets reuse
 * detection distinguish "already rotated" from "never existed". The consequence is a table that only
 * grows: every sign-in adds a row and every refresh adds another. Without a sweep it would accumulate
 * one row per refresh per user forever, which is a slow but certain operational problem.
 *
 * <h2>Why it waits for the retention window</h2>
 *
 * Deleting rows the moment they expire would delete the evidence. When a reuse is detected, the rows
 * recording that family - the reason, the timings, how many tokens were killed - are what an
 * investigation reads, and it will usually start after the tokens themselves have expired. Retention
 * keeps them for a week past expiry by default.
 *
 * <p>Deletion is keyed on {@code expires_at}, which V15 indexes, and is bounded by that index rather
 * than scanning the table.
 *
 * <h2>Scope</h2>
 *
 * <strong>Only expiry decides deletion, never revocation.</strong> A revoked row whose expiry has not
 * yet passed is kept: it still has to be findable, because a replay of it is precisely the thing reuse
 * detection must catch. Deleting revoked rows early would turn a stolen-token replay back into "unknown
 * token" and lose the signal.
 *
 * <p>Single-instance assumption: with several instances every one of them runs this job. That is
 * harmless - the delete is idempotent and the losers simply delete nothing - but it is not coordinated,
 * and no claim is made that it is.
 */
@Component
public class ExpiredRefreshTokenPurge {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRefreshTokenPurge.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final Duration retention;

    public ExpiredRefreshTokenPurge(RefreshTokenRepository refreshTokenRepository,
                                    SecurityProperties properties,
                                    Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.retention = properties.session().expiredRetention();
    }

    @Scheduled(cron = "${app.security.session.purge-cron:0 15 3 * * *}")
    @Transactional
    public void purge() {
        int deleted = purgeNow();
        if (deleted > 0) {
            log.info("Purged {} refresh-token row(s) expired more than {} ago", deleted, retention);
        }
    }

    /**
     * The work itself, separated from the schedule so a test can run it at a controlled time instead of
     * waiting for cron. Returns the number of rows deleted.
     */
    @Transactional
    public int purgeNow() {
        LocalDateTime cutoff = LocalDateTime
                .ofInstant(clock.instant(), SessionTimeConfig.STORAGE_ZONE)
                .minus(retention);
        return refreshTokenRepository.deleteExpiredBefore(cutoff);
    }
}
