package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportDocumentDto;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.dto.ReportDocumentsDto;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentStatus;
import com.faction.clientportal.model.ReportDocumentType;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tracks the per-document lifecycle of a report generation run (DOCX, PDF,
 * encrypted PDF) and owns the per-assessment report password used for the
 * encrypted PDF variant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDocumentService {

    private static final int REPORT_PASSWORD_LENGTH = 16;

    private final ReportDocumentRepository reportDocumentRepository;
    private final AssessmentRepository     assessmentRepository;
    private final EncryptionService        encryptionService;
    private final ReportEncryptor          reportEncryptor;
    private final EditionPolicy editionPolicy;

    /**
     * Marks every document type as GENERATING ahead of an async generation
     * run. Existing fileId/generatedAt values are kept so the last good file
     * stays downloadable while the new one is produced.
     */
    public void startGeneration(String assessmentId) {
        startGeneration(assessmentId, java.util.EnumSet.allOf(ReportDocumentType.class));
    }

    /**
     * Marks only the given document types as GENERATING — used when an
     * uploaded PDF only affects the PDF/encrypted-PDF pair and the DOCX
     * should be left as-is.
     */
    public void startGeneration(String assessmentId, java.util.Collection<ReportDocumentType> types) {
        for (ReportDocumentType type : types) {
            if (!isProducible(type)) {
                continue;
            }
            ReportDocument doc = findOrCreate(assessmentId, type);
            doc.setStatus(ReportDocumentStatus.GENERATING);
            doc.setErrorMessage(null);
            doc.setUpdatedAt(LocalDateTime.now());
            reportDocumentRepository.save(doc);
        }
    }

    /**
     * Whether this build can produce a document type at all.
     *
     * <p>Load-bearing in two places, for one reason: the Finalize panel treats *any*
     * document still GENERATING as the whole report still running. A type that gets marked
     * generating and then skipped never resolves, so the spinner runs forever and Preview
     * and Download stay disabled behind it. Filtering on read as well as on write also
     * clears rows left behind by a build that could once produce them.
     */
    private boolean isProducible(ReportDocumentType type) {
        return type != ReportDocumentType.ENCRYPTED_PDF
                || editionPolicy.enabled(Feature.ENCRYPTED_PDF);
    }

    private boolean isProducible(ReportDocument doc) {
        return isProducible(doc.getDocType());
    }

    public void markCompleted(String assessmentId, ReportDocumentType type, String fileId) {
        ReportDocument doc = findOrCreate(assessmentId, type);
        doc.setStatus(ReportDocumentStatus.COMPLETED);
        doc.setFileId(fileId);
        doc.setGeneratedAt(LocalDateTime.now());
        doc.setErrorMessage(null);
        doc.setUpdatedAt(LocalDateTime.now());
        reportDocumentRepository.save(doc);
    }

    public void markFailed(String assessmentId, ReportDocumentType type, String errorMessage) {
        ReportDocument doc = findOrCreate(assessmentId, type);
        doc.setStatus(ReportDocumentStatus.FAILED);
        doc.setErrorMessage(truncate(errorMessage));
        doc.setUpdatedAt(LocalDateTime.now());
        reportDocumentRepository.save(doc);
    }

    /**
     * Marks any document still GENERATING as FAILED. Called when the async
     * generation run aborts so the frontend never spins forever.
     */
    public void failStuckDocuments(String assessmentId, String errorMessage) {
        for (ReportDocument doc : reportDocumentRepository.findByAssessmentId(assessmentId)) {
            if (doc.getStatus() == ReportDocumentStatus.GENERATING) {
                doc.setStatus(ReportDocumentStatus.FAILED);
                doc.setErrorMessage(truncate(errorMessage));
                doc.setUpdatedAt(LocalDateTime.now());
                reportDocumentRepository.save(doc);
            }
        }
    }

    /**
     * Returns the storage key for the requested document type, if one exists.
     * Falls back to the legacy {@code assessment.generatedReportFileId} for
     * DOCX so reports generated before this feature stay downloadable.
     */
    public Optional<String> findFileId(Assessment assessment, ReportDocumentType type) {
        Optional<String> fileId = reportDocumentRepository
                .findByAssessmentIdAndDocType(assessment.getId(), type)
                .map(ReportDocument::getFileId)
                .filter(id -> id != null && !id.isBlank());
        if (fileId.isEmpty() && type == ReportDocumentType.DOCX) {
            return Optional.ofNullable(assessment.getGeneratedReportFileId());
        }
        return fileId;
    }

    /**
     * Returns the per-document statuses plus the decrypted report password
     * for display in the Finalize panel.
     */
    public ReportDocumentsDto getDocuments(Assessment assessment) {
        List<ReportDocumentDto> documents = reportDocumentRepository
                .findByAssessmentId(assessment.getId())
                .stream()
                .filter(this::isProducible)
                .sorted(Comparator.comparing(ReportDocument::getDocType))
                .map(ReportDocumentDto::fromEntity)
                .collect(Collectors.toList());

        // Legacy assessments: report generated before per-document tracking
        if (documents.isEmpty() && assessment.getGeneratedReportFileId() != null) {
            documents = List.of(ReportDocumentDto.builder()
                    .type(ReportDocumentType.DOCX)
                    .status(ReportDocumentStatus.COMPLETED)
                    .available(true)
                    .generatedAt(assessment.getReportGeneratedAt())
                    .build());
        }

        // Not merely absent — withheld. A database that once ran the paid edition still
        // holds these rows, so leaving this to "there is no password" would hand the
        // plaintext to a build that no longer has the feature.
        String password = null;
        if (editionPolicy.enabled(Feature.ENCRYPTED_PDF) && assessment.getReportPasswordEncrypted() != null) {
            try {
                password = encryptionService.decrypt(assessment.getReportPasswordEncrypted());
            } catch (Exception e) {
                log.warn("Could not decrypt report password for assessment {}: {}",
                        assessment.getId(), e.getMessage());
            }
        }

        return ReportDocumentsDto.builder()
                .documents(documents)
                .reportPassword(password)
                .build();
    }

    /**
     * Returns the plaintext report password for the assessment, generating and
     * persisting one (encrypted at rest) on first use.
     *
     * @throws IllegalStateException when no encryption key is configured
     */
    public String ensureReportPassword(Assessment assessment) {
        // The choke point for the encrypted variant: nothing can produce one without a
        // password, so guarding here covers generation and direct upload alike.
        editionPolicy.require(Feature.ENCRYPTED_PDF);

        if (assessment.getReportPasswordEncrypted() != null) {
            return encryptionService.decrypt(assessment.getReportPasswordEncrypted());
        }
        if (!encryptionService.isConfigured()) {
            throw new IllegalStateException(
                    "SSO_ENCRYPTION_KEY is not configured — cannot store the encrypted PDF password. "
                    + "Generate one with: openssl rand -base64 32");
        }
        String password = reportEncryptor.generatePassword(REPORT_PASSWORD_LENGTH);
        assessment.setReportPasswordEncrypted(encryptionService.encrypt(password));
        assessmentRepository.save(assessment);
        return password;
    }

    private ReportDocument findOrCreate(String assessmentId, ReportDocumentType type) {
        return reportDocumentRepository
                .findByAssessmentIdAndDocType(assessmentId, type)
                .orElseGet(() -> ReportDocument.builder()
                        .assessmentId(assessmentId)
                        .docType(type)
                        .status(ReportDocumentStatus.GENERATING)
                        .build());
    }

    private String truncate(String message) {
        if (message == null) return "Report generation failed";
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
