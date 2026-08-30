package com.faction.clientportal.dto;

import com.faction.clientportal.model.ReportTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight DTO for ReportTemplate list views
 * Excludes CSS and field definitions for performance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportTemplateSummaryDto {

    private String id;
    private String name;
    private String description;
    private String assessmentTypeId;
    private String templateFileName;
    private Long templateFileSize;
    private Integer version;
    private Integer fieldCount;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Convert from entity to summary DTO
     */
    public static ReportTemplateSummaryDto fromEntity(ReportTemplate entity) {
        if (entity == null) {
            return null;
        }
        return ReportTemplateSummaryDto.builder()
            .id(entity.getId())
            .name(entity.getName())
            .description(entity.getDescription())
            .assessmentTypeId(entity.getAssessmentTypeId())
            .templateFileName(entity.getTemplateFileName())
            .templateFileSize(entity.getTemplateFileSize())
            .version(entity.getVersion())
            .fieldCount(entity.getUserDefinedFields() != null ? entity.getUserDefinedFields().size() : 0)
            .active(entity.getActive())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }
}
