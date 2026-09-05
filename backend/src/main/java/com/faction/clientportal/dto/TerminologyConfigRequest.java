package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A terminology update, where <em>omitted means unchanged</em>.
 *
 * <p>Deliberately not the entity. {@code TerminologyConfig} carries {@code @Builder.Default}
 * values so a fresh installation reads correctly, which means a body of
 * {@code {"severityCritical": "P1"}} deserializes into an object claiming the organization is
 * called "Organization" — and the save would quietly undo a rename the customer had already made.
 * Every field here starts null instead, so the service can tell "leave it alone" from "set it to
 * this". The interface sends the whole object either way; this is for anyone driving the API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminologyConfigRequest {
    private String organizationSingular;
    private String organizationPlural;
    private String subOrganizationSingular;
    private String subOrganizationPlural;
    private String severityCritical;
    private String severityHigh;
    private String severityMedium;
    private String severityLow;
    private String severityInformational;
}
