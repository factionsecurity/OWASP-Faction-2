package com.faction.clientportal.model;

/**
 * Enum representing the lifecycle status of an assessment.
 */
public enum AssessmentStatus {
    /**
     * Initial state - assessment created but work not yet started
     */
    DRAFT,

    /**
     * Assessment work is actively being conducted
     */
    IN_PROGRESS,

    /**
     * Assessment work temporarily paused
     */
    ON_HOLD,

    /**
     * Assessment work complete, awaiting review
     */
    PENDING_REVIEW,

    /**
     * Assessment completed and report finalized
     */
    COMPLETED,

    /**
     * Assessment approved by stakeholders
     */
    APPROVED,

    /**
     * Assessment archived for historical reference
     */
    ARCHIVED
}
