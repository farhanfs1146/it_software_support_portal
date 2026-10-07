package com.forward.it_software_support_portal.security.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * The clock the session machinery reads, and the scheduler the purge job needs.
 *
 * <h2>Why a Clock bean rather than {@code LocalDateTime.now()}</h2>
 *
 * Every rule in this package is a statement about time: a refresh token is usable until it expires, a
 * cached account state is fresh for 15 seconds, an expired row is deleted after the retention window.
 * Tested against the wall clock, those rules can only be verified by sleeping - which makes the suite
 * slow and, worse, flaky, so the rules end up either untested or trusted. Injecting the clock lets a
 * test move time by seven days and one second and assert the outcome exactly, with no sleeping. The
 * expiry tests in this phase all work that way, as the login limiter's already did.
 *
 * <h2>Why UTC</h2>
 *
 * The schema stores timestamps as {@code TIMESTAMP} without a zone, matching every other timestamp
 * column in this database. A column with no zone is only unambiguous if everything writing to it agrees
 * on one, so the clock is fixed to UTC rather than taking the host's default. Otherwise moving the
 * application to a host in another timezone - or a daylight-saving change on the same host - would
 * silently shift every token lifetime, and an hour-long jump backwards would make already-issued tokens
 * look unexpired.
 */
@Configuration
@EnableScheduling
public class SessionTimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** The zone every stored {@code LocalDateTime} in this package is expressed in. */
    public static final ZoneOffset STORAGE_ZONE = ZoneOffset.UTC;
}
