package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * What this build includes, and how much of each cap is used.
 *
 * <p>Read once at sign-in and cached for the session: it is the single source the
 * frontend consults to decide whether to render a feature or its upgrade prompt, so
 * every diamond badge in the UI traces back to this one response.
 *
 * <p>Keys are {@code Feature.getKey()} and {@code Quota.getKey()} rather than enum
 * names — those keys are the stable contract with the frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditionStatusDto {

    /** {@code COMMUNITY} or {@code ENTERPRISE}. */
    private String edition;

    /** Feature key to availability, every feature present. */
    private Map<String, Boolean> features;

    /** Quota key to cap. Unlimited quotas are omitted, so "absent" means "no limit". */
    private Map<String, Integer> limits;

    /** Quota key to current count, every quota present. */
    private Map<String, Long> usage;

    /** Where the UI sends someone who wants what they cannot have. */
    private String upgradeUrl;
}
