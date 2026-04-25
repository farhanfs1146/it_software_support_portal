package com.forward.it_software_support_portal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_attachments")
@Getter
@Setter
public class TicketAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;

    private String filePath;

    private String fileType;

    private LocalDateTime uploadedAt;

//    @ManyToOne
//    @JoinColumn(name = "ticket_id")
//    private Ticket ticket;
}
