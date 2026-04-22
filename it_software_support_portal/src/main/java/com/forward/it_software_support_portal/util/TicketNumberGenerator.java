package com.forward.it_software_support_portal.util;

import org.springframework.stereotype.Component;

@Component
public class TicketNumberGenerator {

    public String generate() {
        return "TKT-" + System.currentTimeMillis();
    }
}
