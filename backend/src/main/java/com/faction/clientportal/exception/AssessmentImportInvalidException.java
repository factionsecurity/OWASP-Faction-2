package com.faction.clientportal.exception;

import com.faction.clientportal.dto.AssessmentImportPreviewDto;

/** A commit refused because rows failed validation; carries the fresh preview for the 400. */
public class AssessmentImportInvalidException extends RuntimeException {

    private final transient AssessmentImportPreviewDto preview;

    public AssessmentImportInvalidException(AssessmentImportPreviewDto preview) {
        super(preview.getErrorCount() + " row(s) have errors; nothing was imported");
        this.preview = preview;
    }

    public AssessmentImportPreviewDto getPreview() {
        return preview;
    }
}
