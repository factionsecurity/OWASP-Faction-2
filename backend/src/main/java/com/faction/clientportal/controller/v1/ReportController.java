package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.ReportDocumentType;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.ReportDocumentsDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.service.AssessmentWorkflowConfigService;
import com.faction.clientportal.service.ReportDocumentService;
import com.faction.clientportal.service.ReportGenerationTrigger;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.FileStreamResponse;
import com.faction.clientportal.util.LibreOfficeConverter;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reports", description = "Report generation endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private static final long MAX_UPLOAD_SIZE = 1073741824L; // 1GB, matches global multipart limit
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ReportGenerationTrigger reportGenerationTrigger;
    private final com.faction.clientportal.service.AccessScopeService accessScopeService;
    private final AssessmentRepository    assessmentRepository;
    private final StorageService          storageService;
    private final LibreOfficeConverter    libreOfficeConverter;
    private final ReportDocumentService   reportDocumentService;
    private final AssessmentWorkflowConfigService workflowConfigService;

    /**
     * A completed assessment's report is the deliverable of record — regenerating or replacing it
     * would silently change what was already issued. Reopen the assessment first (see
     * {@code AssessmentService.REOPEN_WINDOW_DAYS}) if it genuinely needs another report.
     *
     * <p>Downloads are deliberately unaffected: reading the issued report must keep working.
     */
    private void requireOpen(Assessment assessment, String action) {
        if (workflowConfigService.isCompletedStatus(assessment.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot " + action + " a completed assessment. Reopen it first.");
        }
    }

    @PostMapping("/{assessmentId}/generate")
    @RequiresPermission({Permission.REPORTING_CREATE, Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Trigger background report generation for an assessment",
        description = "Starts an async job that generates the report from the assessment's "
                + "DOCX template, stores it in MinIO, and updates the assessment with "
                + "the download key. Returns 202 immediately.",
        responses = {
            @ApiResponse(responseCode = "202", description = "Report generation started"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<Void>> generateReport(
            @PathVariable String assessmentId,
            Authentication authentication) {

        // Verify the assessment exists before accepting the request
        var assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));
        requireOpen(assessment, "regenerate the report for");

        String userId = authentication.getName();

        // Mark all document types as generating synchronously so the panel
        // shows per-document progress from the very first poll.
        reportDocumentService.startGeneration(assessmentId);
        reportGenerationTrigger.trigger(assessmentId, userId);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JsonApiResponse.success(
                        "Report generation started. The report will be available shortly.",
                        (Void) null));
    }

    @PostMapping("/{assessmentId}/upload")
    @RequiresPermission({Permission.REPORTING_CREATE, Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Upload a DOCX or PDF report, replacing the generated artifacts",
        description = "Uploading a DOCX replaces the DOCX artifact and regenerates both the PDF "
                + "and encrypted PDF from it. Uploading a PDF replaces only the plain PDF and "
                + "regenerates the encrypted PDF from it, leaving the DOCX untouched. Returns 202 "
                + "immediately; poll GET /{assessmentId}/documents for progress.",
        responses = {
            @ApiResponse(responseCode = "202", description = "Report upload accepted"),
            @ApiResponse(responseCode = "400", description = "File missing, too large, or not a DOCX/PDF"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<Void>> uploadReport(
            @PathVariable String assessmentId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws IOException {

        var assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));
        requireOpen(assessment, "upload a report to");

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(JsonApiResponse.error("File is empty"));
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            return ResponseEntity.badRequest()
                    .body(JsonApiResponse.error("File size exceeds maximum allowed size of 1GB"));
        }

        String contentType = file.getContentType();
        ReportDocumentType uploadedType;
        Set<ReportDocumentType> markGenerating;
        if (DOCX_CONTENT_TYPE.equals(contentType)) {
            uploadedType   = ReportDocumentType.DOCX;
            markGenerating = EnumSet.allOf(ReportDocumentType.class);
        } else if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            uploadedType   = ReportDocumentType.PDF;
            markGenerating = EnumSet.of(ReportDocumentType.PDF, ReportDocumentType.ENCRYPTED_PDF);
        } else {
            return ResponseEntity.badRequest().body(JsonApiResponse.error(
                    "File must be a DOCX or PDF document. Received: " + contentType));
        }

        String userId = authentication.getName();
        byte[] fileBytes = file.getBytes();

        // Mark the affected document types as generating synchronously so the
        // panel shows per-document progress from the very first poll.
        reportDocumentService.startGeneration(assessmentId, markGenerating);
        reportGenerationTrigger.triggerUpload(assessmentId, userId, fileBytes, uploadedType);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JsonApiResponse.success(
                        "Report upload accepted. Processing will complete shortly.",
                        (Void) null));
    }

    @GetMapping("/{assessmentId}/documents/{type}/content")
    @RequiresPermission({Permission.REPORTING_DOWNLOAD, Permission.REPORTING_DOWNLOAD_OWNED, Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Download a generated report",
        description = "Streams the generated report's bytes as an attachment download.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Report streamed"),
            @ApiResponse(responseCode = "404", description = "Assessment or report not found"),
        }
    )
    public ResponseEntity<Resource> downloadReport(
            @PathVariable String assessmentId,
            @PathVariable String type,
            Authentication authentication) {

        var assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));
        accessScopeService.checkAssessmentAccess(authentication, assessment);

        ReportDocumentType docType;
        try {
            docType = ReportDocumentType.valueOf(type.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown report document type: " + type);
        }

        String fileId = reportDocumentService.findFileId(assessment, docType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No generated " + docType + " report found for assessment: " + assessmentId
                        + ". Trigger generation first via POST /api/v1/reports/{assessmentId}/generate"));

        return FileStreamResponse.attachment(
                storageService.openStream(fileId), reportFileName(assessment.getName(), docType));
    }

    /**
     * A human-meaningful save-as name, since the storage key is a generated id.
     * Anything that could confuse a filesystem or a Content-Disposition parser is
     * collapsed to a hyphen.
     */
    private static String reportFileName(String assessmentName, ReportDocumentType docType) {
        String base = (assessmentName == null || assessmentName.isBlank()) ? "report" : assessmentName;
        String safe = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        String suffix = docType == ReportDocumentType.DOCX ? ".docx" : ".pdf";
        return (safe.isEmpty() ? "report" : safe)
                + (docType == ReportDocumentType.ENCRYPTED_PDF ? "-encrypted" : "")
                + suffix;
    }

    @GetMapping("/{assessmentId}/documents")
    @RequiresPermission({Permission.REPORTING_DOWNLOAD, Permission.REPORTING_DOWNLOAD_OWNED, Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Get per-document generation status for an assessment's report",
        description = "Returns the status, last-generated timestamp, and availability of each "
                + "report artifact (DOCX, PDF, encrypted PDF), plus the password for the "
                + "encrypted PDF once provisioned.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Document statuses returned"),
            @ApiResponse(responseCode = "404", description = "Assessment not found"),
        }
    )
    public ResponseEntity<JsonApiResponse<ReportDocumentsDto>> getReportDocuments(
            @PathVariable String assessmentId,
            Authentication authentication) {

        var assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));
        accessScopeService.checkAssessmentAccess(authentication, assessment);

        return ResponseUtil.success("Report documents retrieved",
                reportDocumentService.getDocuments(assessment));
    }

    @GetMapping("/{assessmentId}/pdf")
    @RequiresPermission({Permission.REPORTING_DOWNLOAD, Permission.REPORTING_DOWNLOAD_OWNED, Permission.ASSESSMENTS_READ_ALL, Permission.ASSESSMENTS_READ_TEAM, Permission.ASSESSMENTS_READ_ASSIGNED, Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(
        summary = "Convert the generated DOCX report to PDF and return it inline",
        description = "Downloads the stored DOCX report, converts it to PDF via LibreOffice "
                + "headless, and streams the result. Suitable for in-browser preview.",
        responses = {
            @ApiResponse(responseCode = "200", description = "PDF bytes returned"),
            @ApiResponse(responseCode = "404", description = "Assessment or report not found"),
            @ApiResponse(responseCode = "500", description = "Conversion failed"),
        }
    )
    public ResponseEntity<byte[]> getReportAsPdf(@PathVariable String assessmentId,
            Authentication authentication) {
        var assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));
        accessScopeService.checkAssessmentAccess(authentication, assessment);

        if (assessment.getGeneratedReportFileId() == null) {
            throw new ResourceNotFoundException(
                    "No generated report found for assessment: " + assessmentId);
        }

        try {
            byte[] docxBytes = storageService.downloadBytes(assessment.getGeneratedReportFileId());
            byte[] pdfBytes  = libreOfficeConverter.convertToPdf(docxBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"" + assessment.getName().replaceAll("[^\\w\\-.]", "_") + ".pdf\"");

            return ResponseEntity.ok().headers(headers).body(pdfBytes);

        } catch (IOException | InterruptedException e) {
            log.error("PDF conversion failed for assessment {}: {}", assessmentId, e.getMessage(), e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
