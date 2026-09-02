package com.faction.clientportal.dto;

import com.faction.clientportal.model.ContentTemplateScope;
import lombok.Data;

/** Create/update request for a content template. On update, null fields mean "no change". */
@Data
public class SaveContentTemplateRequest {
    private String name;
    /** Empty string clears the description */
    private String description;
    private ContentTemplateScope scope;
    private String content;
    private Boolean enabled;
}
