package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request DTO for updating an existing report template
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateReportTemplateRequest {

    @Size(max = 255, message = "Template name must not exceed 255 characters")
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    private String assessmentTypeId;

    @Size(max = 10485760, message = "CSS must not exceed 10MB")
    private String css;

    @Size(max = 255, message = "Font must not exceed 255 characters")
    private String font;

    /**
     * The colour palette, or null to leave it alone. The designer sends the whole template on
     * every save, so a null here means "this edit was not about colours" rather than "clear them".
     */
    private com.faction.clientportal.model.ReportPalette reportPalette;

    private List<String> sections;

    @Valid
    private List<UserDefinedFieldDto> userDefinedFields;

    private Boolean active;

    private String scoringType;
}
