package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a URL associated with a specific engagement/assessment.
 * Examples: test environment URLs, staging URLs, internal documentation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngagementUrl {
    /**
     * The URL itself
     */
    private String url;

    /**
     * Description of what this URL is for (e.g., "Staging Environment", "Test Login")
     */
    private String description;
}
