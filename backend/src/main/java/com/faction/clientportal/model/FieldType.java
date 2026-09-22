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

    /**
     * Single-line text whose value is also its destination. The report renders it as a real
     * hyperlink: an email address becomes {@code mailto:}, a URL or bare hostname becomes a web
     * link, and a value holding several of them — separated by commas, semicolons or spaces —
     * becomes one link per address on the same line. See
     * {@code com.faction.clientportal.util.reporting.SmartLink}.
     */
    HYPERLINK,

}
