package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiPromptTemplateDto {

    private String id;
    private String name;
    private String description;
    private AiPromptScope scope;
    private String prompt;
    private String providerId;
    private String model;
    private boolean allowWebAccess;
    private boolean enabled;

    public static AiPromptTemplateDto fromEntity(AiPromptTemplate template) {
        return AiPromptTemplateDto.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .scope(template.getScope())
                .prompt(template.getPrompt())
                .providerId(template.getProviderId())
                .model(template.getModel())
                .allowWebAccess(template.isAllowWebAccess())
                .enabled(template.isEnabled())
                .build();
    }
}
