package com.faction.clientportal.model;

/**
 * Enum representing the types of user-defined fields in report templates.
 * Supports various input types for flexible report customization.
 */
public enum FieldType {
    /**
     * Single-line text input
     */
    STRING,

    /**
     * Multi-line rich text editor with formatting support
     */
    RICH_TEXT,

    /**
     * Single-select dropdown
     */
    DROPDOWN,

}
