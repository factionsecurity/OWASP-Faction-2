package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * Request DTO for creating a new report template
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReportTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 255, message = "Template name must not exceed 255 characters")
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @NotBlank(message = "Assessment type ID is required")
    private String assessmentTypeId;

    @Size(max = 10485760, message = "CSS must not exceed 10MB")
    private String css;

    private String font;

    private String scoringType;

    @Builder.Default
    private List<String> sections = new ArrayList<>();

    @Valid
    @Builder.Default
    private List<UserDefinedFieldDto> userDefinedFields = new ArrayList<>();
}
