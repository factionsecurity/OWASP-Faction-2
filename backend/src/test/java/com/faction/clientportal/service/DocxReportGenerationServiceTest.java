package com.faction.clientportal.service;

import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.edition.UnrestrictedEditionPolicy;
import com.faction.clientportal.service.ReportEncryptor;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocxReportGenerationService}.
 *
 * <p>These tests exercise business-logic decisions (auth, missing data) without
 * spinning up a Spring context or connecting to external services.
 */
@ExtendWith(MockitoExtension.class)
class DocxReportGenerationServiceTest {

    @Mock private AssessmentRepository            assessmentRepository;
    @Mock private ReportTemplateRepository        reportTemplateRepository;
    @Mock private VulnerabilityRepository         vulnerabilityRepository;
    @Mock private UserRepository                  userRepository;
    @Mock private AssessmentTypeRepository        assessmentTypeRepository;
    @Mock private VulnerabilityCategoryRepository vulnCategoryRepository;
    @Mock private InlineImageRepository           inlineImageRepository;
    @Mock private StorageService                  storageService;
    @Mock private ReportDocumentService           reportDocumentService;
    @Mock private com.faction.clientportal.util.LibreOfficeConverter libreOfficeConverter;
    @Mock private ReportEncryptor         reportEncryptor;

    // These suites describe enterprise behaviour, so they run under the real
    // enterprise policy rather than a mock — a bare mock reports every feature as
    // off, which would quietly skip the encryption these tests are about.
    @Spy private com.faction.clientportal.edition.EditionPolicy editionPolicy =
            new com.faction.clientportal.edition.UnrestrictedEditionPolicy();

    @InjectMocks
    private DocxReportGenerationService service;

    private Assessment baseAssessment;

    @BeforeEach
    void setUp() {
        baseAssessment = Assessment.builder()
                .id("asmt-1")
                .name("Test Assessment")
                .applicationId("app-1")
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .templateFileId("templates/tmpl-1/template.docx")
                .templateCss("")
                .scoringType("NATIVE")
                .status("IN_PROGRESS")
                .assessorIds(List.of())
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void generateReport_throwsResourceNotFoundWhenAssessmentMissing() {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("missing-id"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateReport("missing-id", "user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing-id");
    }

    @Test
    void generateReport_throwsIllegalStateWhenNoTemplateFile() {
        baseAssessment.setTemplateFileId(null);
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));

        assertThatThrownBy(() -> service.generateReport("asmt-1", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template");
    }

    /**
     * Field values are keyed by field ID in some editors and by variableName in
     * the vulnerability editor. buildFieldMaps must resolve both, or STRING
     * UDFs like ${test2} render blank in the vulnerability table.
     */
    @Test
    void buildFieldMaps_resolvesValuesKeyedByIdOrVariableName() throws Exception {
        java.lang.reflect.Method m = DocxReportGenerationService.class.getDeclaredMethod(
                "buildFieldMaps", List.class, java.util.Map.class, java.util.Map.class, java.util.Map.class);
        m.setAccessible(true);

        List<UserDefinedField> defs = List.of(
                UserDefinedField.builder().id("id-1").variableName("byId")
                        .fieldType(FieldType.STRING).build(),
                UserDefinedField.builder().id("id-2").variableName("byName")
                        .fieldType(FieldType.STRING).build(),
                UserDefinedField.builder().id("id-3").variableName("missing")
                        .fieldType(FieldType.STRING).defaultValue("fallback").build());
        java.util.Map<String, String> stored = java.util.Map.of(
                "id-1", "value keyed by id",
                "byName", "value keyed by variable name");

        java.util.Map<String, String> outValues = new java.util.HashMap<>();
        java.util.Map<String, FieldType> outTypes = new java.util.HashMap<>();
        m.invoke(service, defs, stored, outValues, outTypes);

        org.assertj.core.api.Assertions.assertThat(outValues)
                .containsEntry("byId", "value keyed by id")
                .containsEntry("byName", "value keyed by variable name")
                .containsEntry("missing", "fallback");
    }

    @Test
    void generateReport_backfillsTemplateFileFromLiveTemplate() {
        // Assessment snapshotted before a DOCX was uploaded to the template
        baseAssessment.setTemplateFileId(null);
        baseAssessment.setReportTemplateId("tmpl-1");
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));
        when(reportTemplateRepository.findById("tmpl-1"))
                .thenReturn(Optional.of(ReportTemplate.builder()
                        .id("tmpl-1")
                        .templateFileId("report-templates/tmpl-1/report.docx")
                        .build()));
        // Sentinel: proves generation got past the template check to the download step
        when(storageService.downloadBytes("report-templates/tmpl-1/report.docx"))
                .thenThrow(new RuntimeException("SENTINEL"));

        assertThatThrownBy(() -> service.generateReport("asmt-1", "user-1"))
                .hasMessageContaining("SENTINEL");
        org.assertj.core.api.Assertions.assertThat(baseAssessment.getTemplateFileId())
                .isEqualTo("report-templates/tmpl-1/report.docx");
    }

    @Test
    void generateReport_throwsIllegalStateWhenTemplateHasNoFileEither() {
        baseAssessment.setTemplateFileId(null);
        baseAssessment.setReportTemplateId("tmpl-1");
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));
        when(reportTemplateRepository.findById("tmpl-1"))
                .thenReturn(Optional.of(ReportTemplate.builder().id("tmpl-1").build()));

        assertThatThrownBy(() -> service.generateReport("asmt-1", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no DOCX file");
    }

    @Test
    void generateReport_throwsWhenTemplateDownloadFails() {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));
        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(List.of());
        when(inlineImageRepository.findByAssessmentId("asmt-1"))
                .thenReturn(List.of());
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.empty());
        when(storageService.downloadBytes(anyString()))
                .thenThrow(new RuntimeException("MinIO connection refused"));

        assertThatThrownBy(() -> service.generateReport("asmt-1", "user-1"))
                .isInstanceOf(RuntimeException.class);
    }

    // ── uploadReport ─────────────────────────────────────────────────────────

    @Test
    void uploadReport_throwsResourceNotFoundWhenAssessmentMissing() {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("missing-id"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadReport(
                "missing-id", "docx bytes".getBytes(), ReportDocumentType.DOCX, "user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing-id");
    }

    @Test
    void uploadReport_docxUpload_storesDocxAndRegeneratesBothPdfVariants() throws Exception {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));

        byte[] docxBytes = "uploaded docx".getBytes();
        byte[] pdfBytes  = "converted pdf".getBytes();
        byte[] encrypted = "encrypted pdf".getBytes();
        when(libreOfficeConverter.convertToPdf(docxBytes)).thenReturn(pdfBytes);
        when(reportDocumentService.ensureReportPassword(baseAssessment)).thenReturn("pw123");
        when(reportEncryptor.encrypt(pdfBytes, "pw123")).thenReturn(encrypted);

        service.uploadReport("asmt-1", docxBytes, ReportDocumentType.DOCX, "user-1");

        verify(storageService).uploadBytes(anyString(), eq(docxBytes), eq(REPORT_CONTENT_TYPE));
        verify(storageService).uploadBytes(anyString(), eq(pdfBytes), eq(PDF_CONTENT_TYPE));
        verify(storageService).uploadBytes(anyString(), eq(encrypted), eq(PDF_CONTENT_TYPE));
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.DOCX), anyString());
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.PDF), anyString());
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.ENCRYPTED_PDF), anyString());
        org.assertj.core.api.Assertions.assertThat(baseAssessment.getGeneratedReportFileId()).isNotNull();
        verify(assessmentRepository, atLeastOnce()).save(baseAssessment);
    }

    /**
     * The open source edition still produces the DOCX and the plain PDF — only the
     * encrypted variant is missing, and it is skipped rather than marked failed. Marking
     * it failed would report a broken report run for a document that was never coming.
     */
    @Test
    void uploadReport_communityEdition_producesPlainVariantsAndSkipsEncryption() throws Exception {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));
        org.mockito.Mockito.doReturn(false).when(editionPolicy)
                .enabled(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);

        byte[] docxBytes = "uploaded docx".getBytes();
        byte[] pdfBytes  = "converted pdf".getBytes();
        when(libreOfficeConverter.convertToPdf(docxBytes)).thenReturn(pdfBytes);

        service.uploadReport("asmt-1", docxBytes, ReportDocumentType.DOCX, "user-1");

        verify(storageService).uploadBytes(anyString(), eq(docxBytes), eq(REPORT_CONTENT_TYPE));
        verify(storageService).uploadBytes(anyString(), eq(pdfBytes), eq(PDF_CONTENT_TYPE));
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.DOCX), anyString());
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.PDF), anyString());

        verify(reportDocumentService, never())
                .markCompleted(anyString(), eq(ReportDocumentType.ENCRYPTED_PDF), anyString());
        verify(reportDocumentService, never())
                .markFailed(anyString(), eq(ReportDocumentType.ENCRYPTED_PDF), anyString());
        verify(reportDocumentService, never()).ensureReportPassword(any());
    }

    @Test
    void uploadReport_pdfUpload_storesPdfDirectlyWithoutConversionAndLeavesDocxAlone() throws Exception {
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1"))
                .thenReturn(Optional.of(baseAssessment));

        byte[] pdfBytes  = "uploaded pdf".getBytes();
        byte[] encrypted = "encrypted pdf".getBytes();
        when(reportDocumentService.ensureReportPassword(baseAssessment)).thenReturn("pw123");
        when(reportEncryptor.encrypt(pdfBytes, "pw123")).thenReturn(encrypted);

        service.uploadReport("asmt-1", pdfBytes, ReportDocumentType.PDF, "user-1");

        verify(libreOfficeConverter, org.mockito.Mockito.never()).convertToPdf(any());
        verify(storageService).uploadBytes(anyString(), eq(pdfBytes), eq(PDF_CONTENT_TYPE));
        verify(storageService).uploadBytes(anyString(), eq(encrypted), eq(PDF_CONTENT_TYPE));
        verify(reportDocumentService, org.mockito.Mockito.never())
                .markCompleted(anyString(), eq(ReportDocumentType.DOCX), anyString());
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.PDF), anyString());
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.ENCRYPTED_PDF), anyString());
    }

    private static final String REPORT_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
}
