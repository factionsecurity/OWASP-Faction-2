package com.faction.clientportal.service;

import com.faction.clientportal.model.UserDefinedField;

import java.util.List;

/**
 * A CSV layout the application sync accepts besides its own template — typically another system's
 * inventory export, uploaded as-is. Core ships none; the overlay contributes them as beans, and
 * {@link ApplicationCsvImportService} hands a file to the first one that recognizes its header.
 *
 * <p>A format only translates. It rewrites the file into the template's columns and the sync then
 * runs exactly as it would on a template upload: the same matching, validation and per-row errors.
 */
public interface ApplicationCsvFormat {

    /**
     * Prefix of a template column holding an application custom field, followed by the field's
     * {@code variableName} — {@code field:tier}. Formats use it to carry columns that have no
     * built-in home; a hand-written template upload may use it too.
     */
    String FIELD_COLUMN_PREFIX = "field:";

    /** Shown to the uploader as the format that was detected, e.g. "Acme CMDB export". */
    String name();

    /**
     * Whether this deployment accepts the format at all — a customer-specific layout is usually
     * behind a switch. The upload dialog lists available formats; an unavailable one is never
     * offered a file.
     */
    default boolean available() {
        return true;
    }

    /** Whether this header row is this format's. Cells arrive trimmed, case as written. */
    boolean recognizes(List<String> header);

    /**
     * Application custom fields this format writes. Any whose {@code variableName} doesn't exist
     * yet is created before the sync; an existing one is used as an admin left it.
     */
    default List<UserDefinedField> customFields() {
        return List.of();
    }

    /**
     * Rewrites the rows, header first, into the template's layout. Must return exactly one row per
     * input row and in the same order, so the line numbers in the result still point at the file
     * the user uploaded.
     */
    List<List<String>> toTemplate(List<List<String>> rows);
}
