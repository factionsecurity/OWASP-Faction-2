package com.faction.clientportal.model;

/**
 * Enum representing the scope of a user-defined field.
 * Determines which part of the application the field applies to.
 */
public enum FieldScope {
    /**
     * Field is used in assessment reports
     */
    ASSESSMENT,

    /**
     * Field is used for individual vulnerability records
     */
    VULNERABILITY,

    /**
     * Field is used for application records
     */
    APPLICATION,

    /**
     * Field is used for organization records
     */
    ORGANIZATION,
}
