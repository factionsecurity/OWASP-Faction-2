package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * An application returned by an {@code ApplicationInventory} extension — a record
 * that lives in some external system of record (a CMDB, an asset inventory), not
 * in Faction.
 *
 * <p>Deliberately a separate type from {@link ApplicationDto}. These records have
 * no Faction id and are not persisted, so returning them as ApplicationDto would
 * put rows with null ids into the applications list, where every action the UI
 * offers — open, edit, schedule — would fail on them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalApplicationDto {

    /** The identifier used by the source system, not a Faction id. */
    private String applicationId;

    private String applicationName;

    /** Semicolon-separated email addresses, as the extender API defines it. */
    private String distributionList;

    /**
     * Custom field values keyed by the Faction variable name they map onto, so a
     * caller can prefill an application form from the external record.
     */
    private Map<String, String> customFields;
}
