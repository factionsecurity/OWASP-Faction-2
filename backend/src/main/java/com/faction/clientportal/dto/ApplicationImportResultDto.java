package com.faction.clientportal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of a CSV application sync: what was written, and what wasn't.
 *
 * <p>A bad row does not abort the run — the rows around it are still applied — so the caller needs
 * both the counts and the per-row reasons to know what to fix and re-upload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationImportResultDto {

    /** Data rows read from the file (the header is not counted). */
    private int processed;

    private int created;
    private int updated;
    private int failed;

    /** Organizations created because a row named one that didn't exist yet. */
    @Builder.Default
    private List<String> createdOrganizations = new ArrayList<>();

    /** Sub-organizations created, rendered as "Organization / Division". */
    @Builder.Default
    private List<String> createdSubOrganizations = new ArrayList<>();

    public List<String> getCreatedSubOrganizations() {
        return new ArrayList<>(createdSubOrganizations);
    }

    /**
     * Records an organization created to satisfy a row.
     *
     * <p>Like {@link #addError}, this exists because the getters hand back copies:
     * appending through one updates a throwaway and the name never reaches the response.
     */
    public void addCreatedOrganization(String name) {
        if (name == null) return;
        createdOrganizations.add(name);
    }

    /** Records a sub-organization created to satisfy a row, as "Organization / Division". */
    public void addCreatedSubOrganization(String name) {
        if (name == null) return;
        createdSubOrganizations.add(name);
    }

    public void setCreatedSubOrganizations(List<String> createdSubOrganizations) {
        this.createdSubOrganizations = createdSubOrganizations == null
                ? new ArrayList<>()
                : new ArrayList<>(createdSubOrganizations);
    }

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    public List<RowError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public void setErrors(List<RowError> errors) {
        this.errors = (errors == null) ? new ArrayList<>() : new ArrayList<>(errors);
    }

    /**
     * Records one rejected row.
     *
     * <p>Exists because {@link #getErrors()} hands back an unmodifiable view, so callers
     * cannot append through it. Adding here keeps the internal list unreachable — which is
     * the point of that view — while still giving the importer a supported way to report a
     * bad row.
     */
    public void addError(RowError error) {
        if (error == null) return;
        errors.add(error);
    }

    /** One rejected row, identified by its line in the uploaded file. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        @Schema(description = "1-based line number in the uploaded file, header included",
                example = "4")
        private int line;

        /** The row's name or appId, so the user can find it without counting lines. */
        private String identifier;

        private String message;
    }
}
