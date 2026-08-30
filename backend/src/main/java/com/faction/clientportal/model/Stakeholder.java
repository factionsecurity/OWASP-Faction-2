package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a stakeholder for a specific assessment/engagement.
 * Can be copied from the Application's stakeholders or added specifically for this engagement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Stakeholder {
    /**
     * Stakeholder's name
     */
    private String name;

    /**
     * Stakeholder's email address
     */
    private String email;

    /**
     * Stakeholder's role (e.g., "Product Owner", "Technical Lead", "Security Champion")
     */
    private String role;
}
