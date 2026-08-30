package com.faction.clientportal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for cloning a report template. The name is the only thing the caller chooses — everything
 * else is copied verbatim from the source template.
 */
@Data
public class CloneReportTemplateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    @Schema(description = "Name for the new template; must not match an existing template",
            example = "Web App Pentest (Copy)")
    private String name;
}
