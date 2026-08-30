package com.faction.clientportal.dto;

import com.faction.clientportal.model.SubOrganization;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A division within an organization, plus how many applications are attributed to it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubOrganizationDto {

    private String id;
    private String organizationId;

    /**
     * The owning organization's name. Only populated by the cross-organization directory listing,
     * where the caller has no parent in hand to look it up from.
     */
    @Schema(description = "Name of the owning organization (directory listing only)")
    private String organizationName;

    private String name;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Applications pointing at this sub-organization. Non-zero blocks deletion. */
    @Schema(description = "Number of applications attributed to this sub-organization")
    private long applicationCount;

    public static SubOrganizationDto fromEntity(SubOrganization entity, long applicationCount) {
        return fromEntity(entity, applicationCount, null);
    }

    public static SubOrganizationDto fromEntity(SubOrganization entity, long applicationCount,
                                                String organizationName) {
        return SubOrganizationDto.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .organizationName(organizationName)
                .name(entity.getName())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .applicationCount(applicationCount)
                .build();
    }

    /** Create/update body — the organization comes from the path, so only these are settable. */
    @Data
    public static class Request {
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        private String name;

        @Size(max = 255, message = "Description must be at most 255 characters")
        private String description;
    }
}
