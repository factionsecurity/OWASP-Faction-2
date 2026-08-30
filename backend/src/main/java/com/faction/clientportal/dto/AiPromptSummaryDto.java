package com.faction.clientportal.dto;

import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.model.AiPromptTemplate;
import lombok.Builder;
import lombok.Data;

/** Prompt info exposed to non-admin users in the editor AI menu — never includes the prompt text. */
@Data
@Builder
public class AiPromptSummaryDto {

    private String id;
    private String name;
    private String description;
    private AiPromptScope scope;

    public static AiPromptSummaryDto fromEntity(AiPromptTemplate template) {
        return AiPromptSummaryDto.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .scope(template.getScope())
                .build();
    }
}
