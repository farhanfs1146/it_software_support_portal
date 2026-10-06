package com.forward.it_software_support_portal.entity;

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

    /**
     * Optimistic locking for the Ticket aggregate (audit finding P1-4).
     *
     * <p>Ticket is the only entity this application mutates after creation, and the only one the
     * audit showed taking concurrent writes, so it is the correct - and the only - place for a
     * version column. Hibernate increments it on every update and refuses a write carrying a stale
     * version, which surfaces as OptimisticLockingFailureException and then HTTP 409.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    @ManyToOne
    @JoinColumn(name = "raised_by", nullable = false)
    private User raisedBy;

    @ManyToOne
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @ManyToOne
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "module_name", nullable = false, length = 100)
    private String moduleName;
}
