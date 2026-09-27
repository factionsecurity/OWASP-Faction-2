package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An assessment with retests finished (or evidence edited) since its last retest report.
 * The counts cover only those new retests, not everything the report would include.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetestReportReadyDto {
    private String assessmentId;
    private String assessmentName;
    private String applicationId;
    private String applicationName;
    private int passedCount;
    private int failedCount;
    private LocalDateTime lastCompletedAt;
    private LocalDateTime retestReportGeneratedAt;
    private boolean hasRetestTemplate;
}
