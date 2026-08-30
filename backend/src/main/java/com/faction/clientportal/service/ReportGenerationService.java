package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.model.ReportDocumentType;

public interface ReportGenerationService {

    /**
     * Generate a report for the given assessment, store it, and return
     * the updated AssessmentDto with generatedReportFileId and reportGeneratedAt set.
     */
    AssessmentDto generateReport(String assessmentId, String userId);

    /**
     * Processes a manually uploaded report file, replacing the generated
     * artifacts for the assessment.
     *
     * <p>A {@code DOCX} upload replaces the DOCX and regenerates both the PDF
     * and encrypted PDF from it. A {@code PDF} upload replaces only the plain
     * PDF and regenerates the encrypted PDF from it, leaving the DOCX
     * untouched. No other type is supported.
     */
    void uploadReport(String assessmentId, byte[] fileBytes, ReportDocumentType uploadedType, String userId);
}
