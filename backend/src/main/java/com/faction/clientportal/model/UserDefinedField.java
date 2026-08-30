package com.faction.clientportal.model;

import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a custom field definition in a report template.
 * This is an embedded document (not a separate collection).
 * Used for both template definitions and assessment field snapshots.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDefinedField {

    /**
     * Unique identifier for this field (UUID)
     */
    private String id;

    /**
     * Variable name used in templates (e.g., "executive_summary")
     * Must be alphanumeric with underscores only
     */
    private String variableName;

    /**
     * Human-readable display name (e.g., "Executive Summary")
     */
    private String displayName;

    /**
     * Help text to guide users when filling in this field
     */
    private String helpText;

    /**
     * Type of field (STRING, RICH_TEXT, DROPDOWN, etc.)
     */
    private FieldType fieldType;

    /**
     * Options for DROPDOWN and MULTI_SELECT field types
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> dropdownOptions = new ArrayList<>();

    /**
     * Default value when field is first created
     */
    private String defaultValue;

    /**
     * Whether this field must be filled in
     */
    @Builder.Default
    private Boolean required = false;

    /**
     * Maximum length for STRING and RICH_TEXT types
     */
    private Integer maxLength;

    /**
     * Minimum length for STRING and RICH_TEXT types
     */
    private Integer minLength;

    /**
     * Display order in the UI (lower numbers appear first)
     */
    private Integer displayOrder;

    /**
     * Scope of this field — which part of the application it applies to.
     * Defaults to ASSESSMENT for backwards compatibility.
     */
    @Builder.Default
    private FieldScope fieldScope = FieldScope.ASSESSMENT;

    /**
     * A detached copy of this field, including its id and variable name. Used when cloning a
     * report template: the fields must be identical (so the DOCX's {@code ${...}} references still
     * resolve) but must not be the same objects, or editing one template's field list would mutate
     * the other's. {@code dropdownOptions} is copied into a fresh list for the same reason.
     */
    public UserDefinedField copy() {
        return UserDefinedField.builder()
                .id(id)
                .variableName(variableName)
                .displayName(displayName)
                .helpText(helpText)
                .fieldType(fieldType)
                .dropdownOptions(dropdownOptions == null ? new ArrayList<>() : new ArrayList<>(dropdownOptions))
                .defaultValue(defaultValue)
                .required(required)
                .maxLength(maxLength)
                .minLength(minLength)
                .displayOrder(displayOrder)
                .fieldScope(fieldScope)
                .build();
    }
}
