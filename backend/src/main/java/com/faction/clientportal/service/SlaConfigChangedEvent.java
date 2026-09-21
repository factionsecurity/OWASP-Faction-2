package com.faction.clientportal.service;

/** An SLA edit on one workflow; its open findings' stored due dates must be recalculated. */
public record SlaConfigChangedEvent(String workflowId) {
}
