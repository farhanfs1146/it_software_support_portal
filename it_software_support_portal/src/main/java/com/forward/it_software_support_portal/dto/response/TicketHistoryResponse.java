package com.forward.it_software_support_portal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One entry from a ticket's audit trail.
 *
 * <p>The trail has been written since Phase 2 - every create, assignment and status change appends a
 * row - but nothing could read it: there was no endpoint, and the repository's only finder was
 * unused. An audit trail that cannot be read is not an audit trail, which is what this response
 * exists to fix.
 */
@Data
@Builder
public class TicketHistoryResponse {

    private Long id;

    @Schema(description = "What happened", example = "STATUS_CHANGED")
    private String actionType;

    @Schema(description = "The column that changed", example = "status")
    private String fieldName;

    @Schema(description = "Value before the change; null when there was none")
    private String oldValue;

    @Schema(description = "Value after the change")
    private String newValue;

    @Schema(description = "Full name of the user who made the change")
    private String changedBy;

    @Schema(description = "Free-text note recorded with the change")
    private String remarks;

    private LocalDateTime changedAt;
}
