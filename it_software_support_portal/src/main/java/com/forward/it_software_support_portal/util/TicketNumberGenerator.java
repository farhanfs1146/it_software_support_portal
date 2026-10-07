package com.forward.it_software_support_portal.util;

import com.forward.it_software_support_portal.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generates unique ticket numbers (audit finding P0-5).
 *
 * <p><strong>What was wrong.</strong> This class, and a duplicate inline expression in
 * {@code TicketServiceImpl}, built the number as {@code "TKT-" + System.currentTimeMillis()}. That
 * has no uniqueness guarantee at all: a measured 200 simultaneous calls produced only 68 distinct
 * values, with 185 calls sharing a number. Each duplicate beyond the first violates
 * {@code tickets_ticket_number_key} and reaches the user as HTTP 500.
 *
 * <p><strong>How it is fixed.</strong> The numeric part now comes from the PostgreSQL sequence
 * {@code ticket_number_seq}. Correctness lives in the database, which is the only place that can
 * arbitrate between concurrent transactions. Specifically this avoids:
 * <ul>
 *   <li>{@code synchronized}, which only serialises one JVM and breaks the moment a second instance
 *       runs - and this application is meant to scale horizontally;
 *   <li>retry-on-duplicate loops, which paper over a generator that is still wrong;
 *   <li>swallowing duplicate-key exceptions;
 *   <li>sleeping to force the clock forward.
 * </ul>
 *
 * <p><strong>Format.</strong> The external shape is preserved as {@code TKT-} followed by digits, so
 * both old and new numbers match {@code TKT-\d+} and existing rows need no migration. Only the
 * meaning of the digits changes, from an epoch-millisecond value to a zero-padded sequence value.
 */
@Component
@RequiredArgsConstructor
public class TicketNumberGenerator {

    static final String PREFIX = "TKT-";

    /**
     * Minimum digit width. Values beyond {@code 99_999_999} simply grow wider - {@code %0Nd} pads,
     * it never truncates - so the sequence is not capped by the format.
     */
    static final int MIN_DIGITS = 8;

    private final TicketRepository ticketRepository;

    public String generate() {
        return format(ticketRepository.nextTicketNumberValue());
    }

    static String format(long sequenceValue) {
        return PREFIX + String.format("%0" + MIN_DIGITS + "d", sequenceValue);
    }
}
