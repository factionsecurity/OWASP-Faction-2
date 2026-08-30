package com.faction.clientportal.service;

import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.util.LibreOfficeConverter;
import com.faction.clientportal.util.reporting.DocxUtils;
import com.faction.clientportal.util.reporting.ReportData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import com.sun.star.beans.PropertyValue;
import com.sun.star.frame.XComponentLoader;
import com.sun.star.frame.XController;
import com.sun.star.frame.XDispatchHelper;
import com.sun.star.frame.XDispatchProvider;
import com.sun.star.frame.XFrame;
import com.sun.star.frame.XModel;
import com.sun.star.lang.XComponent;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.util.XCloseable;
import com.sun.star.frame.XStorable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements report generation using docx4j template processing.
 *
 * <p>Ships in the core: producing a report is what the open source edition is for.
 * Only the password-protected variant is paid, and that goes through
 * {@link ReportEncryptor}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load assessment + vulnerabilities + related data from MongoDB</li>
 *   <li>Download the DOCX template from MinIO</li>
 *   <li>Build a {@link ReportData} container</li>
 *   <li>Run {@link DocxUtils#generateDocx} to fill the template</li>
 *   <li>Upload the resulting DOCX to MinIO</li>
 *   <li>Store the MinIO key in {@code assessment.generatedReportFileId}</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocxReportGenerationService implements ReportGenerationService {

    private final AssessmentRepository          assessmentRepository;
    private final EditionPolicy                 editionPolicy;
    private final ReportTemplateRepository      reportTemplateRepository;
    private final VulnerabilityRepository       vulnerabilityRepository;
    private final com.faction.clientportal.repository.VulnerabilityStageCompletionRepository stageCompletionRepository;
    private final UserRepository                userRepository;
    private final AssessmentTypeRepository      assessmentTypeRepository;
    private final VulnerabilityCategoryRepository vulnCategoryRepository;
    private final InlineImageRepository         inlineImageRepository;
    private final StorageService                storageService;
    private final ReportDocumentService         reportDocumentService;
    private final LibreOfficeConverter          libreOfficeConverter;
    private final ReportEncryptor               reportEncryptor;
    private final com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;

    /**
     * The order findings appear in a report: most severe first, Informational last.
     *
     * <p>Previously this was {@code display_order} alone. Nothing sets that but
     * creation order — {@code VulnerabilityService.nextOrder} hands out max+1 and the
     * UI never calls the reorder endpoint — so findings came out in whatever sequence
     * the tester happened to enter them. An Informational noted first therefore led
     * the report, ahead of every Critical, and the report disagreed with the
     * assessment screen, which has always listed by severity.
     *
     * <p>{@code display_order} stays as the tie-break, so within one severity the
     * tester's entry order is still what the reader sees.
     */
    private static final Comparator<Vulnerability> REPORT_ORDER =
            Comparator.<Vulnerability>comparingInt(
                            v -> VulnerabilitySeverity.reportRankOf(v.getSeverity()))
                    .thenComparingInt(v -> v.getOrder() == null ? 0 : v.getOrder());

    private static final String REPORT_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    // ── ReportGenerationService ──────────────────────────────────────────────

    @Override
    public AssessmentDto generateReport(String assessmentId, String userId) {
        log.info("Starting report generation for assessment {} by user {}", assessmentId, userId);

        // 1. Load assessment
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));

        // Backfill the snapshot from the live template — assessments created
        // before a DOCX was uploaded to the template snapshotted a null file id.
        if (assessment.getTemplateFileId() == null && assessment.getReportTemplateId() != null) {
            reportTemplateRepository.findById(assessment.getReportTemplateId())
                    .map(ReportTemplate::getTemplateFileId)
                    .ifPresent(assessment::setTemplateFileId);
        }

        if (assessment.getTemplateFileId() == null) {
            throw new IllegalStateException(
                    "The report template has no DOCX file attached. "
                    + "Upload a DOCX template in the report designer first.");
        }

        // 2. Load vulnerabilities, most severe first
        List<Vulnerability> vulns = vulnerabilityRepository
                .findByAssessmentIdAndDeletedAtIsNull(assessmentId)
                .stream()
                .sorted(REPORT_ORDER)
                .collect(Collectors.toList());

        // 3. Load assessors
        List<User> assessors = loadUsers(assessment.getAssessorIds());

        // 4. Load remediation manager
        User remediationManager = assessment.getRemediationManagerId() != null
                ? userRepository.findById(assessment.getRemediationManagerId()).orElse(null)
                : null;

        // 5. Look up assessment-type name
        String assessmentTypeName = assessment.getAssessmentTypeId() != null
                ? assessmentTypeRepository.findById(assessment.getAssessmentTypeId())
                        .map(AssessmentType::getName).orElse("")
                : "";

        // 6. Build category lookup map
        Map<String, String> categoryNames = buildCategoryNames(vulns);

        // 7. Load inline images for all rich-text content
        Map<String, byte[]>   imageBytes        = new HashMap<>();
        Map<String, String>   imageContentTypes = new HashMap<>();
        loadInlineImages(assessmentId, imageBytes, imageContentTypes);

        // 8. Download template DOCX from MinIO
        byte[] templateBytes = storageService.downloadBytes(assessment.getTemplateFileId());

        // 9. Build ReportData
        ReportData reportData = buildReportData(
                assessment, assessors, remediationManager, assessmentTypeName,
                vulns, categoryNames, imageBytes, imageContentTypes);

        // 9b. Let ReportManager extensions rewrite the report's rich text, and get a
        //     resolver for the placeholders they own inside the DOCX template itself
        DocxUtils.TokenResolver extensionTokens =
                applyReportExtensions(assessment, vulns, reportData);

        // 10. Generate the populated DOCX
        byte[] reportBytes = generateDocxBytes(templateBytes, reportData,
                assessment.getTemplateCss() == null ? "" : assessment.getTemplateCss(),
                assessment.getTemplateFont(), extensionTokens);

        // 11. Upload to MinIO
        long   runTimestamp = System.currentTimeMillis();
        String reportKey    = buildReportKey(assessmentId, runTimestamp, "docx");
        storageService.uploadBytes(reportKey, reportBytes, REPORT_CONTENT_TYPE);
        log.info("Uploaded report for assessment {} to key: {}", assessmentId, reportKey);

        // 12. Update assessment with report metadata
        assessment.setGeneratedReportFileId(reportKey);
        assessment.setReportGeneratedAt(LocalDateTime.now());
        assessment.setLastUpdatedBy(userId);
        assessment.setUpdatedAt(LocalDateTime.now());
        assessmentRepository.save(assessment);

        // DOCX is ready — mark it downloadable before the slower PDF stages run
        reportDocumentService.markCompleted(assessmentId, ReportDocumentType.DOCX, reportKey);

        // 13. Convert to PDF, then produce the password-protected variant
        generatePdfVariants(assessment, reportBytes, runTimestamp);

        log.info("Report generation complete for assessment {}", assessmentId);

        // Return a minimal DTO with just the updated report fields
        return AssessmentDto.builder()
                .id(assessment.getId())
                .generatedReportFileId(reportKey)
                .reportGeneratedAt(assessment.getReportGeneratedAt())
                .build();
    }

    /**
     * Produces the PDF and encrypted-PDF variants of an already generated DOCX.
     * Each stage records its own success/failure so the Finalize panel can show
     * per-document state; a PDF failure also fails the encrypted variant since
     * it can't be produced without the plain PDF.
     */
    private void generatePdfVariants(Assessment assessment, byte[] docxBytes, long runTimestamp) {
        String assessmentId = assessment.getId();

        byte[] pdfBytes;
        try {
            pdfBytes = libreOfficeConverter.convertToPdf(docxBytes);
            String pdfKey = buildReportKey(assessmentId, runTimestamp, "pdf");
            storageService.uploadBytes(pdfKey, pdfBytes, PDF_CONTENT_TYPE);
            reportDocumentService.markCompleted(assessmentId, ReportDocumentType.PDF, pdfKey);
            log.info("Uploaded PDF report for assessment {} to key: {}", assessmentId, pdfKey);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("PDF conversion failed for assessment {}: {}", assessmentId, e.getMessage(), e);
            reportDocumentService.markFailed(assessmentId, ReportDocumentType.PDF,
                    "PDF conversion failed: " + e.getMessage());
            reportDocumentService.markFailed(assessmentId, ReportDocumentType.ENCRYPTED_PDF,
                    "Skipped — PDF conversion failed");
            return;
        }

        encryptAndStorePdf(assessment, pdfBytes, runTimestamp);
    }

    /**
     * Encrypts an already-produced PDF and stores it as the ENCRYPTED_PDF
     * variant. Shared by the DOCX-driven generation flow and by a direct
     * PDF upload, which skips straight to this step.
     */
    private void encryptAndStorePdf(Assessment assessment, byte[] pdfBytes, long runTimestamp) {
        String assessmentId = assessment.getId();

        // Skipped outright in the open source edition rather than left to fail. The DOCX
        // and plain PDF are the deliverable there, and marking the encrypted variant
        // "failed" would report a broken report run for a document that was never coming.
        if (!editionPolicy.enabled(Feature.ENCRYPTED_PDF)) {
            return;
        }

        try {
            String password  = reportDocumentService.ensureReportPassword(assessment);
            byte[] encrypted = reportEncryptor.encrypt(pdfBytes, password);
            String encryptedKey = String.format("reports/%s/report-%d-encrypted.pdf",
                    assessmentId, runTimestamp);
            storageService.uploadBytes(encryptedKey, encrypted, PDF_CONTENT_TYPE);
            reportDocumentService.markCompleted(assessmentId, ReportDocumentType.ENCRYPTED_PDF, encryptedKey);
            log.info("Uploaded encrypted PDF report for assessment {} to key: {}", assessmentId, encryptedKey);
        } catch (Exception e) {
            log.error("Encrypted PDF generation failed for assessment {}: {}",
                    assessmentId, e.getMessage(), e);
            reportDocumentService.markFailed(assessmentId, ReportDocumentType.ENCRYPTED_PDF,
                    "Encrypted PDF generation failed: " + e.getMessage());
        }
    }

    // ── ReportGenerationService — uploaded report ────────────────────────────

    /**
     * Processes a manually uploaded DOCX or PDF report, replacing the
     * corresponding generated artifacts.
     *
     * <ul>
     *   <li>DOCX upload — stores it as the DOCX artifact, then converts it to
     *       PDF and encrypts that PDF, exactly like {@link #generateReport}.</li>
     *   <li>PDF upload — stores it as the PDF artifact directly (no
     *       conversion) and encrypts it; the DOCX artifact is left as-is.</li>
     * </ul>
     */
    @Override
    public void uploadReport(String assessmentId, byte[] fileBytes, ReportDocumentType uploadedType, String userId) {
        log.info("Processing uploaded {} report for assessment {} by user {}",
                uploadedType, assessmentId, userId);

        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment not found: " + assessmentId));

        long runTimestamp = System.currentTimeMillis();

        assessment.setReportGeneratedAt(LocalDateTime.now());
        assessment.setLastUpdatedBy(userId);
        assessment.setUpdatedAt(LocalDateTime.now());

        switch (uploadedType) {
            case DOCX -> {
                String docxKey = buildReportKey(assessmentId, runTimestamp, "docx");
                storageService.uploadBytes(docxKey, fileBytes, REPORT_CONTENT_TYPE);
                assessment.setGeneratedReportFileId(docxKey);
                assessmentRepository.save(assessment);
                reportDocumentService.markCompleted(assessmentId, ReportDocumentType.DOCX, docxKey);
                log.info("Uploaded DOCX report for assessment {} to key: {}", assessmentId, docxKey);

                generatePdfVariants(assessment, fileBytes, runTimestamp);
            }
            case PDF -> {
                assessmentRepository.save(assessment);

                String pdfKey = buildReportKey(assessmentId, runTimestamp, "pdf");
                storageService.uploadBytes(pdfKey, fileBytes, PDF_CONTENT_TYPE);
                reportDocumentService.markCompleted(assessmentId, ReportDocumentType.PDF, pdfKey);
                log.info("Uploaded PDF report for assessment {} to key: {}", assessmentId, pdfKey);

                encryptAndStorePdf(assessment, fileBytes, runTimestamp);
            }
            default -> throw new IllegalArgumentException("Unsupported uploaded report type: " + uploadedType);
        }

        log.info("Report upload processing complete for assessment {}", assessmentId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<User> loadUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return userIds.stream()
                .map(id -> userRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Map<String, String> buildCategoryNames(List<Vulnerability> vulns) {
        Set<String> categoryIds = vulns.stream()
                .map(Vulnerability::getVulnerabilityCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = new HashMap<>();
        for (String catId : categoryIds) {
            vulnCategoryRepository.findByIdAndDeletedAtIsNull(catId)
                    .ifPresent(cat -> names.put(catId, cat.getName()));
        }
        return names;
    }

    private void loadInlineImages(String assessmentId,
                                   Map<String, byte[]> imageBytes,
                                   Map<String, String> imageContentTypes) {
        List<InlineImage> images = inlineImageRepository.findByAssessmentId(assessmentId);
        for (InlineImage img : images) {
            try {
                byte[] bytes = storageService.downloadBytes(img.getStorageKey());
                imageBytes.put(img.getId(), bytes);
                imageContentTypes.put(img.getId(),
                        img.getContentType() != null ? img.getContentType() : "image/png");
            } catch (Exception e) {
                log.warn("Could not download inline image {} for assessment {}: {}",
                        img.getId(), assessmentId, e.getMessage());
            }
        }
    }

    private ReportData buildReportData(
            Assessment assessment,
            List<User> assessors,
            User remediationManager,
            String assessmentTypeName,
            List<Vulnerability> vulns,
            Map<String, String> categoryNames,
            Map<String, byte[]> imageBytes,
            Map<String, String> imageContentTypes) {

        // Assessment-level UDF maps (variableName → value / type)
        Map<String, String>    asmtFieldValues = new HashMap<>();
        Map<String, FieldType> asmtFieldTypes  = new HashMap<>();
        buildFieldMaps(assessment.getFieldDefinitions(), assessment.getFieldValues(),
                asmtFieldValues, asmtFieldTypes);

        // Vulnerabilities. The ${closedInDevAt}/${closedInStagingAt} template variables survive the
        // move to configurable remediation stages: they resolve from the completion events recorded
        // against the default "development"/"staging" stage ids, batch-fetched for the whole report.
        Map<String, Map<String, java.time.LocalDateTime>> stageDatesByVuln = new HashMap<>();
        List<String> vulnIds = vulns.stream().map(Vulnerability::getId).collect(Collectors.toList());
        if (!vulnIds.isEmpty()) {
            for (var completion : stageCompletionRepository.findByVulnerabilityIdIn(vulnIds)) {
                stageDatesByVuln
                        .computeIfAbsent(completion.getVulnerabilityId(), k -> new HashMap<>())
                        .put(completion.getStageId(), completion.getCompletedAt());
            }
        }
        List<ReportData.ReportVulnerability> reportVulns = vulns.stream()
                .map(v -> buildReportVuln(v, categoryNames,
                        stageDatesByVuln.getOrDefault(v.getId(), Map.of())))
                .collect(Collectors.toList());

        // Assessor DTOs
        List<ReportData.ReportUser> reportAssessors = assessors.stream()
                .map(u -> ReportData.ReportUser.builder()
                        .firstName(u.getFirstName())
                        .lastName(u.getLastName())
                        .email(u.getEmail())
                        .build())
                .collect(Collectors.toList());

        String remName = remediationManager != null
                ? ((remediationManager.getFirstName() == null ? "" : remediationManager.getFirstName())
                        + " " + (remediationManager.getLastName() == null ? "" : remediationManager.getLastName())).trim()
                : "";

        return ReportData.builder()
                .assessmentId(assessment.getId())
                .assessmentName(assessment.getName())
                .applicationId(assessment.getApplicationId())
                .startDate(assessment.getStartDate())
                .endDate(assessment.getPlannedEndDate())
                .scoringType(assessment.getScoringType())
                .remediationManagerName(remName)
                .assessmentTypeName(assessmentTypeName)
                .assessors(reportAssessors)
                .fieldValues(asmtFieldValues)
                .fieldTypes(asmtFieldTypes)
                .vulnerabilities(reportVulns)
                .inlineImageBytes(imageBytes)
                .inlineImageContentTypes(imageContentTypes)
                .build();
    }

    private ReportData.ReportVulnerability buildReportVuln(
            Vulnerability v, Map<String, String> categoryNames,
            Map<String, java.time.LocalDateTime> stageDates) {

        Map<String, String>    vFieldValues = new HashMap<>();
        Map<String, FieldType> vFieldTypes  = new HashMap<>();
        buildFieldMaps(v.getFieldDefinitions(), v.getFieldValues(), vFieldValues, vFieldTypes);

        return ReportData.ReportVulnerability.builder()
                .id(v.getId())
                .name(v.getName())
                .severity(severityDisplayName(v.getSeverity()))
                .likelihood(v.getLikelihood())
                .impact(v.getImpact())
                .cvssScore(v.getCvssScore())
                .cvssString(v.getCvssString())
                .assetLocation(v.getAssetLocation())
                .description(v.getDescription())
                .recommendation(v.getRecommendation())
                .details(v.getDetails())
                .trackingId(v.getTrackingId())
                .openedAt(v.getOpenedAt())
                .closedAt(v.getClosedAt())
                .closedInDevAt(stageDates.get("development"))
                .closedInStagingAt(stageDates.get("staging"))
                .categoryName(v.getVulnerabilityCategoryId() != null
                        ? categoryNames.get(v.getVulnerabilityCategoryId())
                        : null)
                .fieldValues(vFieldValues)
                .fieldTypes(vFieldTypes)
                .build();
    }

    /**
     * Converts the stored field definitions + values (keyed by field ID)
     * into variableName-keyed maps for DocxUtils.
     */
    private void buildFieldMaps(List<UserDefinedField> definitions,
                                 Map<String, String> storedValues,
                                 Map<String, String> outValues,
                                 Map<String, FieldType> outTypes) {
        if (definitions == null) return;
        for (UserDefinedField field : definitions) {
            String varName = field.getVariableName();
            if (varName == null) continue;
            // Values are keyed by field ID in some editors and by variableName
            // in others (the vulnerability editor) — accept either.
            String value = "";
            if (storedValues != null) {
                value = storedValues.getOrDefault(field.getId(),
                        storedValues.getOrDefault(varName, ""));
            }
            // Fall back to default value if no user input stored
            if (value.isEmpty() && field.getDefaultValue() != null) {
                value = field.getDefaultValue();
            }
            outValues.put(varName, value);
            outTypes.put(varName,
                    field.getFieldType() != null ? field.getFieldType() : FieldType.STRING);
        }
    }

    /**
     * Runs the report's rich text through every enabled {@code ReportManager} extension,
     * and returns a resolver that does the same for placeholders sitting in the DOCX
     * template itself.
     *
     * <p>Faction 1 handed extensions the whole report body as one HTML string, because the
     * report <em>was</em> HTML. Faction 2 fills a DOCX template, and a placeholder can
     * therefore live in two quite different places:
     *
     * <ul>
     *   <li><b>In a rich-text field</b> — the assessment summary, a finding's description.
     *       Handled below by rewriting the field value; it is still HTML on the way to the
     *       XHTML importer, so an extension returning
     *       {@code <img src="data:image/png;base64,…">} embeds correctly.</li>
     *   <li><b>In the DOCX template</b> — alongside {@code ${asmtName}} and
     *       {@code ${summary1}}. This is where a chart naturally goes, since that is where
     *       every other report variable lives. Handled by the returned resolver, which
     *       {@link DocxUtils} calls for each placeholder no built-in variable claimed.</li>
     * </ul>
     *
     * <p>Only RICH_TEXT fields are offered in the first case. A plain STRING field lands in
     * the DOCX as literal text, so returning markup for one would print the tags rather
     * than render them.
     *
     * <p>The assessment and vulnerabilities are mapped once and shared across every field
     * and every placeholder. Cloning them per call would be quadratic on a large report.
     *
     * @return a resolver for template placeholders, or null when no ReportManager is installed
     */
    private DocxUtils.TokenResolver applyReportExtensions(Assessment assessment,
                                                          List<Vulnerability> vulns,
                                                          ReportData reportData) {
        if (!extensionEventService.hasReportManagers()) return null;

        com.faction.elements.Assessment assessmentElement =
                extensionEventService.buildAssessmentElement(assessment);
        List<com.faction.elements.Vulnerability> vulnElements =
                extensionEventService.buildVulnerabilityElements(vulns);

        // Assessment-level rich-text fields
        if (reportData.getFieldValues() != null) {
            reportData.getFieldValues().replaceAll((variableName, value) ->
                    reportData.getFieldType(variableName) == FieldType.RICH_TEXT
                            ? extensionEventService.applyReportManagers(assessmentElement, vulnElements, value)
                            : value);
        }

        // Per-vulnerability narrative and rich-text fields
        if (reportData.getVulnerabilities() != null) {
            for (ReportData.ReportVulnerability vuln : reportData.getVulnerabilities()) {
                vuln.setDescription(extensionEventService.applyReportManagers(
                        assessmentElement, vulnElements, vuln.getDescription()));
                vuln.setRecommendation(extensionEventService.applyReportManagers(
                        assessmentElement, vulnElements, vuln.getRecommendation()));
                vuln.setDetails(extensionEventService.applyReportManagers(
                        assessmentElement, vulnElements, vuln.getDetails()));

                if (vuln.getFieldValues() != null) {
                    vuln.getFieldValues().replaceAll((variableName, value) ->
                            vuln.getFieldType(variableName) == FieldType.RICH_TEXT
                                    ? extensionEventService.applyReportManagers(
                                            assessmentElement, vulnElements, value)
                                    : value);
                }
            }
        }

        return token -> extensionEventService.applyReportManagers(
                assessmentElement, vulnElements, token);
    }

    /** Returns a human-readable severity string, e.g. "Critical". */
    private String severityDisplayName(VulnerabilitySeverity severity) {
        if (severity == null) return "";
        String name = severity.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    private byte[] generateDocxBytes(byte[] templateBytes, ReportData data,
                                      String customCss, String font,
                                      DocxUtils.TokenResolver tokenResolver) {
        try {
            WordprocessingMLPackage mlp = WordprocessingMLPackage.load(
                    new ByteArrayInputStream(templateBytes));
            DocxUtils utils = new DocxUtils(mlp, data);
            if (font != null && !font.isBlank()) {
                utils.FONT = font.trim();
            }
            WordprocessingMLPackage populated = utils.generateDocx(customCss, tokenResolver);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            populated.save(baos);
            return refreshTocWithLibreOffice(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate DOCX report: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the DOCX into a running LibreOffice server via the Java UNO connection pool,
     * dispatches {@code .uno:UpdateAllIndexes} to recalculate TOC page numbers, then saves
     * in place. Falls back to the original bytes if LibreOffice is unavailable.
     */
    private byte[] refreshTocWithLibreOffice(byte[] docxBytes) {
        File tempFile = null;
        XComponent xDoc = null;
        LibreOfficeConnectionPool.PooledConnection pooledConn = null;
        LibreOfficeConnectionPool pool = LibreOfficeConnectionPool.getInstance();

        try {
            tempFile = File.createTempFile("report-toc-", ".docx");
            Files.write(tempFile.toPath(), docxBytes);

            pooledConn = pool.borrowConnection();
            log.debug("Borrowed LibreOffice connection from pool. {}", pool.getPoolStats());

            XComponentLoader loader = UnoRuntime.queryInterface(
                    XComponentLoader.class, pooledConn.getDesktop());

            String docUrl = "file:///" + tempFile.getAbsolutePath().replace("\\", "/");
            xDoc = loader.loadComponentFromURL(docUrl, "_blank", 0, new PropertyValue[0]);

            if (xDoc == null) {
                log.warn("LibreOffice failed to open DOCX — falling back to CLI round-trip");
                pool.invalidateConnection(pooledConn);
                pooledConn = null;
                return normalizeDocxViaCli(docxBytes);
            }

            // Dispatch UpdateAllIndexes to recalculate TOC page numbers
            XModel      xModel      = UnoRuntime.queryInterface(XModel.class, xDoc);
            XController xController = xModel.getCurrentController();
            XFrame      xFrame      = xController.getFrame();

            XDispatchHelper dispatchHelper = UnoRuntime.queryInterface(
                    XDispatchHelper.class,
                    pooledConn.getContext().getServiceManager()
                              .createInstanceWithContext(
                                      "com.sun.star.frame.DispatchHelper",
                                      pooledConn.getContext()));

            XDispatchProvider dispatchProvider =
                    UnoRuntime.queryInterface(XDispatchProvider.class, xFrame);

            dispatchHelper.executeDispatch(
                    dispatchProvider, ".uno:UpdateAllIndexes", "", 0, new PropertyValue[0]);
            log.debug("Dispatched UpdateAllIndexes to LibreOffice");

            // Save in place
            XStorable xStorable = UnoRuntime.queryInterface(XStorable.class, xDoc);
            xStorable.store();

            byte[] refreshed = Files.readAllBytes(tempFile.toPath());
            log.info("TOC page numbers refreshed via LibreOffice UNO connection pool");
            return refreshed;

        } catch (Exception e) {
            log.warn("LibreOffice TOC refresh failed: {} — falling back to CLI round-trip", e.getMessage());
            if (pooledConn != null) {
                pool.invalidateConnection(pooledConn);
                pooledConn = null;
            }
            return normalizeDocxViaCli(docxBytes);
        } finally {
            if (xDoc != null) {
                try {
                    XCloseable xCloseable = UnoRuntime.queryInterface(XCloseable.class, xDoc);
                    if (xCloseable != null) xCloseable.close(true);
                    else                   xDoc.dispose();
                } catch (Exception ignored) {}
            }
            if (pooledConn != null) {
                pool.returnConnection(pooledConn);
                log.debug("Returned LibreOffice connection to pool. {}", pool.getPoolStats());
            }
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    /**
     * Normalizes raw docx4j output by round-tripping it through the LibreOffice
     * CLI (docx → docx). Raw docx4j output contains constructs Microsoft Word
     * rejects as corrupt; a LibreOffice re-save produces a spec-compliant file.
     * Used when the UNO pool (which re-saves as part of the TOC refresh) is
     * unavailable. TOC page numbers are not refreshed on this path.
     */
    private byte[] normalizeDocxViaCli(byte[] docxBytes) {
        try {
            byte[] normalized = libreOfficeConverter.convertToDocx(docxBytes);
            log.info("DOCX normalized via LibreOffice CLI round-trip");
            return normalized;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("LibreOffice CLI round-trip failed: {} — returning raw DOCX "
                    + "(may not open in Microsoft Word)", e.getMessage());
            return docxBytes;
        }
    }

    private String buildReportKey(String assessmentId, long timestamp, String extension) {
        return String.format("reports/%s/report-%d.%s", assessmentId, timestamp, extension);
    }
}
