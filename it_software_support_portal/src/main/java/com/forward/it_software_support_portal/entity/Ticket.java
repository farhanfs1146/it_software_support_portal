package com.forward.it_software_support_portal.entity;

import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String ticketNumber;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private IssueType issueType;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    private String businessImpact;

    private LocalDateTime expectedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime resolvedAt;

//    @ManyToOne
//    @JoinColumn(name = "raised_by")
//    private User raisedBy;
//
//    @ManyToOne
//    @JoinColumn(name = "assigned_to")
//    private User assignedTo;
//
//    @ManyToOne
//    @JoinColumn(name = "application_id")
//    private Application application;
}