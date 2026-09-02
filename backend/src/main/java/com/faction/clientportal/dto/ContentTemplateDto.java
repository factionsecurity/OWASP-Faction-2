package com.faction.clientportal.dto;

import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.ContentTemplateScope;
import lombok.Builder;
import lombok.Data;

/**
 * A content template as the API returns it. The body travels with the list: the picker
 * previews every template inline and inserts without a second round trip, and the content
 * is ordinary boilerplate every authenticated user is allowed to read.
 */
@Data
@Builder
public class ContentTemplateDto {

    private String id;
    private String name;
    private String description;
    private ContentTemplateScope scope;
    private String content;
    private boolean enabled;
    private String createdBy;

    public static ContentTemplateDto fromEntity(ContentTemplate template) {
        return ContentTemplateDto.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .scope(template.getScope())
                .content(template.getContent())
                .enabled(template.isEnabled())
                .createdBy(template.getCreatedBy())
                .build();
    }
}
