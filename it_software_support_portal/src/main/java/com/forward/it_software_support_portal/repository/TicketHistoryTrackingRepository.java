package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.entity.TicketHistoryTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketHistoryTrackingRepository extends JpaRepository<TicketHistoryTracking, Long> {

    List<TicketHistoryTracking> findByTicketIdOrderByChangedAtDesc(Long ticketId);
}
