package com.faction.clientportal.dto;

import com.faction.clientportal.model.WebSearchProviderType;
import lombok.Data;

/** Update request for the web search config. Null fields mean "no change". */
@Data
public class UpdateWebSearchConfigRequest {
    private Boolean enabled;
    private Boolean allowInAskAi;
    private WebSearchProviderType provider;
    /** Masked sentinel preserves the stored key; a real value replaces it */
    private String apiKey;
}
