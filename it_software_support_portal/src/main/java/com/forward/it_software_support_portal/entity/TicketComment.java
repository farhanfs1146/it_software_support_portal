package com.forward.it_software_support_portal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_comments")
@Getter
@Setter
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String comment;

    private LocalDateTime createdAt;

//    @ManyToOne
//    @JoinColumn(name = "ticket_id")
//    private Ticket ticket;
//
//    @ManyToOne
//    @JoinColumn(name = "commented_by")
//    private User commentedBy;
}