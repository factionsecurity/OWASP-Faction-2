package com.faction.clientportal.dto;

import lombok.Data;

/** Update request for anonymization config. Null fields mean "no change". */
@Data
public class UpdateAiAnonymizationConfigRequest {
    private Boolean enabled;
    /** Empty string clears the Presidio URL (falls back to built-in secret patterns only) */
    private String presidioUrl;
    private Double scoreThreshold;
}
