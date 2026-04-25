package com.forward.it_software_support_portal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_history")
@Getter
@Setter
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String action;

    private String oldValue;

    private String newValue;

    private LocalDateTime actionTime;

//    @ManyToOne
//    @JoinColumn(name = "ticket_id")
//    private Ticket ticket;
//
//    @ManyToOne
//    @JoinColumn(name = "performed_by")
//    private User performedBy;
}
