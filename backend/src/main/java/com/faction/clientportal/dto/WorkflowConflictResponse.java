package com.faction.clientportal.dto;

import com.faction.clientportal.exception.WorkflowConflictException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** The 409 body for a refused workflow change: the usual error fields plus every violation with its count. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowConflictResponse {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    private int status;
    private String error;
    private String message;
    private String path;
    private List<WorkflowConflictException.Violation> violations;
}
