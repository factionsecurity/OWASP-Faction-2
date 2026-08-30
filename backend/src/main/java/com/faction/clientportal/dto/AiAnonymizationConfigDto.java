package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiAnonymizationConfig;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiAnonymizationConfigDto {

    private boolean enabled;
    private String presidioUrl;
    private double scoreThreshold;

    public static AiAnonymizationConfigDto fromEntity(AiAnonymizationConfig config) {
        return AiAnonymizationConfigDto.builder()
                .enabled(config.isEnabled())
                .presidioUrl(config.getPresidioUrl())
                .scoreThreshold(config.getScoreThreshold())
                .build();
    }
}
