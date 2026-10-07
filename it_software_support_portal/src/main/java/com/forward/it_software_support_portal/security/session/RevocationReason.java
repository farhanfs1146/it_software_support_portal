package com.forward.it_software_support_portal.security.session;

/**
 * Why a refresh token stopped being usable. Recorded on the row so an investigation can tell a
 * routine rotation from a detected replay, and so the two never have to be inferred from timestamps.
 */
public enum RevocationReason {

    /** Normal rotation: the token was exchanged for a successor in the same family. */
    ROTATED,

    /** The holder called {@code POST /api/auth/logout} with this token. */
    LOGOUT,

    /** The user ended every session, via {@code POST /api/auth/logout-all}. */
    LOGOUT_ALL,

    /**
     * An already-rotated token was presented again. Either a stolen token is being replayed, or the
     * legitimate client is replaying after a thief already rotated. The two are indistinguishable from
     * the server, so the whole family is revoked with this reason.
     */
    REUSE_DETECTED,

    /** The password changed, so every session established with the old one ends. */
    PASSWORD_CHANGED,

    /** An administrator reset the password. */
    PASSWORD_RESET,

    /** The account was deactivated. */
    ACCOUNT_DEACTIVATED,

    /** The role changed, so sessions carrying the old authorities end. */
    ROLE_CHANGED,

    /** An administrator force-signed-out the user without otherwise changing the account. */
    ADMIN_REVOKED,

    /**
     * The user passed the per-user session cap, so their oldest session was dropped to make room.
     * Distinct from {@link #ADMIN_REVOKED} so routine housekeeping is never mistaken, in an
     * investigation, for somebody deliberately signing a user out.
     */
    SESSION_CAP
}
