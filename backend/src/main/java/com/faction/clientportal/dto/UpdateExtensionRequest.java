package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Toggles and ordering for an installed extension. Every field is optional — an
 * absent field leaves the current value alone.
 *
 * <p>A hook can only be switched on if the JAR declares it; the service rejects
 * anything else.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExtensionRequest {

    private Boolean enabled;
    private Integer displayOrder;

    private Boolean assessmentEnabled;
    private Integer assessmentOrder;
    private Boolean vulnerabilityEnabled;
    private Integer vulnerabilityOrder;
    private Boolean verificationEnabled;
    private Integer verificationOrder;
    private Boolean inventoryEnabled;
    private Integer inventoryOrder;
    private Boolean reportEnabled;
    private Integer reportOrder;
}
