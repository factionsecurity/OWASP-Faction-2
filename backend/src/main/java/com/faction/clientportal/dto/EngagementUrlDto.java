package com.faction.clientportal.dto;

import com.faction.clientportal.model.EngagementUrl;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for EngagementUrl embedded document
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngagementUrlDto {
    private String url;
    private String description;

    /**
     * Convert from entity to DTO
     */
    public static EngagementUrlDto fromEntity(EngagementUrl entity) {
        if (entity == null) {
            return null;
        }
        return EngagementUrlDto.builder()
            .url(entity.getUrl())
            .description(entity.getDescription())
            .build();
    }

    /**
     * Convert from DTO to entity
     */
    public EngagementUrl toEntity() {
        return EngagementUrl.builder()
            .url(this.url)
            .description(this.description)
            .build();
    }
}
