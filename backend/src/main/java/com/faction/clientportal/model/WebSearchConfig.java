package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton config for the web search backend used by AI prompt tools.
 * Only prompts with web access enabled can trigger searches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "web_search_config")
public class WebSearchConfig {

    public static final String SINGLETON_ID = "singleton";

    @Id
    @Builder.Default
    private String id = SINGLETON_ID;

    @Builder.Default
    private boolean enabled = false;

    /** When true, freeform "Ask AI" editor queries may use web search and fetch tools */
    @Column(nullable = false)
    @Builder.Default
    private boolean allowInAskAi = false;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WebSearchProviderType provider = WebSearchProviderType.BRAVE;

    /** AES-GCM encrypted API key for the search provider */
    private String encryptedApiKey;
}
