package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentTypeDto {

    private String id;
    private String name;
    private String description;
    private Boolean active;
    /** The workflow new assessments of this type are created under. */
    private String workflowId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
