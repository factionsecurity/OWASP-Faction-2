package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * New values for an extension's declared config keys.
 *
 * <p>Flat {@code key -> value}: the UI only ever edits values, never the types the
 * extension declared in its {@code config.json}. A password value echoed back as
 * the mask means "unchanged" and is ignored, so the UI never has to hold a live
 * secret in order to save an unrelated field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExtensionConfigRequest {

    private Map<String, String> values;
}
