package com.faction.clientportal.service;

/**
 * Published by {@link AssessmentWorkflowConfigService#updateConfig} after a save that changed the
 * vulnerability SLAs, so open findings' stored due dates can be recalculated.
 */
public record SlaConfigChangedEvent() {
}
