package com.faction.clientportal.service;

import com.faction.clientportal.exception.BusinessRuleException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocxReportGenerationService#generateRetestReport}: every finding rendered from the
 * template's retest DOCX with its latest completed retest, stored under the RETEST_* types, and
 * the run recorded with the time it started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetestReportGenerationTest {

    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_TYPE = "application/pdf";
    private static final String RETEST_TEMPLATE_KEY = "report-templates/tmpl-1/retest/r.docx";

    @Mock private AssessmentRepository            assessmentRepository;
    @Mock private ReportTemplateRepository        reportTemplateRepository;
    @Mock private VulnerabilityRepository         vulnerabilityRepository;
    @Mock private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Mock private UserRepository                  userRepository;
    @Mock private AssessmentTypeRepository        assessmentTypeRepository;
    @Mock private VulnerabilityCategoryRepository vulnCategoryRepository;
    @Mock private InlineImageRepository           inlineImageRepository;
    @Mock private StorageService                  storageService;
    @Mock private ReportDocumentService           reportDocumentService;
    @Mock private com.faction.clientportal.util.LibreOfficeConverter libreOfficeConverter;
    @Mock private ReportEncryptor                 reportEncryptor;
    @Mock private com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;
    @Mock private TerminologyConfigService        terminologyConfigService;
    @Mock private RetestReportService             retestReportService;
    @Mock private NotificationService             notificationService;

    @Spy private com.faction.clientportal.edition.EditionPolicy editionPolicy =
            new com.faction.clientportal.edition.UnrestrictedEditionPolicy();

    @InjectMocks
    private DocxReportGenerationService service;

    private Assessment baseAssessment;
    private ReportTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        baseAssessment = Assessment.builder()
                .id("asmt-1")
                .name("Test Assessment")
                .applicationId("app-1")
                .organizationId("org-1")
                .reportTemplateId("tmpl-1")
                .templateFileId("report-templates/tmpl-1/report.docx")
                .templateCss("")
                .scoringType("NATIVE")
                .status("Completed")
                .assessorIds(List.of())
                .generatedReportFileId("reports/asmt-1/report-1.docx")
                .reportGeneratedAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .createdAt(LocalDateTime.now())
                .build();
        template = ReportTemplate.builder()
                .id("tmpl-1")
                .templateFileId("report-templates/tmpl-1/report.docx")
                .retestTemplateFileId(RETEST_TEMPLATE_KEY)
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1")).thenReturn(Optional.of(baseAssessment));
        when(reportTemplateRepository.findById("tmpl-1")).thenReturn(Optional.of(template));
        when(storageService.downloadBytes(RETEST_TEMPLATE_KEY)).thenReturn(retestTemplate());

        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull("asmt-1")).thenReturn(List.of(
                Vulnerability.builder().id("v1").name("SQLi").assessmentId("asmt-1")
                        .severity(VulnerabilitySeverity.HIGH).order(0).build(),
                Vulnerability.builder().id("v2").name("XSS").assessmentId("asmt-1")
                        .severity(VulnerabilitySeverity.MEDIUM).order(1).build()));

        Retest r1 = Retest.builder().id("r1").vulnerabilityId("v1").assessmentId("asmt-1")
                .status("PASSED").closedDate(LocalDateTime.of(2026, 9, 20, 10, 0))
                .completedBy("jane").evidence("<p>Parameterized query confirmed</p>").build();
        when(retestReportService.latestCompletedByVulnerability("asmt-1")).thenReturn(Map.of("v1", r1));
        when(userRepository.findByUsername("jane")).thenReturn(Optional.of(
                User.builder().username("jane").firstName("Jane").lastName("Tester").build()));

        when(terminologyConfigService.severityLabelForName(any())).thenAnswer(inv -> inv.getArgument(0));
        when(terminologyConfigService.severityLabel(any())).thenAnswer(inv -> {
            VulnerabilitySeverity s = inv.getArgument(0);
            return s == null ? "" : s.name();
        });

        // No LibreOffice here: the TOC refresh falls back to the raw DOCX
        when(libreOfficeConverter.convertToDocx(any())).thenThrow(new RuntimeException("no LibreOffice"));
        when(libreOfficeConverter.convertToPdf(any())).thenReturn("pdf".getBytes());
    }

    private byte[] retestTemplate() throws Exception {
        return template(List.of("${fiBegin}", "${vulnName}:${retestResult}", "${fiEnd}"));
    }

    private byte[] template(List<String> paragraphs) throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        for (String p : paragraphs) {
            pkg.getMainDocumentPart().addParagraphOfText(p);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pkg.save(out);
        return out.toByteArray();
    }

    @Test
    void rendersEveryFindingWithRetestColumnsFromTheRetestTemplate() throws Exception {
        service.generateRetestReport("asmt-1", "user-1");

        String xml = renderedXml();

        assertThat(xml).contains("SQLi:Passed").contains("XSS:");
        assertThat(xml).doesNotContain("XSS:Passed").doesNotContain("${retestResult}");
        // The retest DOCX, not the main report's template
        verify(storageService, never()).downloadBytes("report-templates/tmpl-1/report.docx");
    }

    private String renderedXml() throws Exception {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).uploadBytes(contains("/retest-"), bytes.capture(), eq(DOCX_TYPE));
        WordprocessingMLPackage rendered = WordprocessingMLPackage.load(new ByteArrayInputStream(bytes.getValue()));
        return XmlUtils.marshaltoString(rendered.getMainDocumentPart().getJaxbElement(), true, false);
    }

    @Test
    void fillsTheTestersDisplayNameAndTheRetestEvidence() throws Exception {
        when(storageService.downloadBytes(RETEST_TEMPLATE_KEY)).thenReturn(template(List.of(
                "${fiBegin}", "${vulnName}|${retestedBy}|", "${retestEvidence}", "${fiEnd}")));

        service.generateRetestReport("asmt-1", "user-1");

        assertThat(renderedXml()).contains("SQLi|Jane Tester|").contains("XSS||")
                .contains("Parameterized query confirmed");
    }

    @Test
    void theTesterFallsBackToTheUsernameWhenTheUserIsGone() throws Exception {
        when(userRepository.findByUsername("jane")).thenReturn(Optional.empty());
        when(storageService.downloadBytes(RETEST_TEMPLATE_KEY)).thenReturn(template(List.of(
                "${fiBegin}", "${vulnName}|${retestedBy}|", "${fiEnd}")));

        service.generateRetestReport("asmt-1", "user-1");

        assertThat(renderedXml()).contains("SQLi|jane|");
    }

    @Test
    void marksTheRetestDocxCompletedUnderTheRetestType() {
        service.generateRetestReport("asmt-1", "user-1");

        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.RETEST_DOCX), contains("/retest-"));
        verify(reportDocumentService, never()).markCompleted(any(), eq(ReportDocumentType.DOCX), any());
    }

    @Test
    void aPdfFailureFailsOnlyTheRetestPdfTypes() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("no LibreOffice")).when(libreOfficeConverter).convertToPdf(any());

        service.generateRetestReport("asmt-1", "user-1");

        verify(reportDocumentService).markFailed(eq("asmt-1"), eq(ReportDocumentType.RETEST_PDF), anyString());
        verify(reportDocumentService).markFailed(eq("asmt-1"), eq(ReportDocumentType.RETEST_ENCRYPTED_PDF), anyString());
        verify(reportDocumentService, never()).markFailed(any(), eq(ReportDocumentType.PDF), any());
        verify(reportDocumentService, never()).markFailed(any(), eq(ReportDocumentType.ENCRYPTED_PDF), any());
    }

    @Test
    void storesThePdfVariantsUnderRetestKeysAndTypes() throws Exception {
        byte[] pdf = "pdf".getBytes();
        byte[] encrypted = "encrypted".getBytes();
        org.mockito.Mockito.doReturn(pdf).when(libreOfficeConverter).convertToPdf(any());
        when(reportDocumentService.ensureReportPassword(baseAssessment)).thenReturn("pw");
        when(reportEncryptor.encrypt(pdf, "pw")).thenReturn(encrypted);

        service.generateRetestReport("asmt-1", "user-1");

        ArgumentCaptor<String> docxKey = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadBytes(docxKey.capture(), any(), eq(DOCX_TYPE));
        String ts = docxKey.getValue().replaceAll("^reports/asmt-1/retest-(\\d+)\\.docx$", "$1");
        assertThat(ts).matches("\\d+");
        verify(storageService).uploadBytes("reports/asmt-1/retest-" + ts + ".pdf", pdf, PDF_TYPE);
        verify(storageService).uploadBytes("reports/asmt-1/retest-" + ts + "-encrypted.pdf", encrypted, PDF_TYPE);
        verify(reportDocumentService).markCompleted("asmt-1", ReportDocumentType.RETEST_PDF,
                "reports/asmt-1/retest-" + ts + ".pdf");
        verify(reportDocumentService).markCompleted("asmt-1", ReportDocumentType.RETEST_ENCRYPTED_PDF,
                "reports/asmt-1/retest-" + ts + "-encrypted.pdf");
        verify(reportDocumentService, never()).markCompleted(any(), eq(ReportDocumentType.PDF), any());
        verify(reportDocumentService, never()).markCompleted(any(), eq(ReportDocumentType.ENCRYPTED_PDF), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsGenerationWithTheStartTimeAndTheRetestsUsed() {
        AtomicReference<LocalDateTime> firstReadAt = new AtomicReference<>();
        when(assessmentRepository.findByIdAndDeletedAtIsNull("asmt-1")).thenAnswer(inv -> {
            firstReadAt.compareAndSet(null, LocalDateTime.now());
            return Optional.of(baseAssessment);
        });
        AtomicReference<LocalDateTime> completedAt = new AtomicReference<>();
        doAnswer(inv -> { completedAt.set(LocalDateTime.now()); return null; })
                .when(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.RETEST_DOCX), anyString());
        LocalDateTime before = LocalDateTime.now();

        service.generateRetestReport("asmt-1", "user-1");

        ArgumentCaptor<LocalDateTime> started = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(retestReportService).recordGeneration(eq("asmt-1"), started.capture(), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrderElementsOf(Set.of("r1"));
        assertThat(started.getValue()).isAfterOrEqualTo(before).isBeforeOrEqualTo(completedAt.get());
        // Taken before the assessment, or anything else, was read
        assertThat(started.getValue()).isBeforeOrEqualTo(firstReadAt.get());
    }

    @Test
    void aRetestCompletedDuringGenerationStillCountsAsReady() {
        // A retest closes while the report is being rendered, after the retests were read.
        AtomicReference<LocalDateTime> midRunClose = new AtomicReference<>();
        doAnswer(inv -> {
            Thread.sleep(5);
            midRunClose.set(LocalDateTime.now());
            return null;
        }).when(storageService).uploadBytes(contains("/retest-"), any(), eq(DOCX_TYPE));

        service.generateRetestReport("asmt-1", "user-1");

        ArgumentCaptor<LocalDateTime> started = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retestReportService).recordGeneration(eq("asmt-1"), started.capture(), any());
        assertThat(started.getValue()).isBefore(midRunClose.get());

        // With the stamp recorded, that retest is still new since the last report
        Assessment stamped = Assessment.builder().id("asmt-1").retestReportGeneratedAt(started.getValue()).build();
        Retest lateRetest = Retest.builder().id("r-late").vulnerabilityId("v2").status("FAILED")
                .closedDate(midRunClose.get()).build();
        assertThat(RetestReportService.isReady(stamped, List.of(lateRetest))).containsExactly(lateRetest);
    }

    @Test
    void theEncryptedPdfPasswordSaveCarriesTheRetestStamp() throws Exception {
        // ensureReportPassword saves the in-memory assessment when it mints the first password;
        // that save must not write back the stale (pre-run) retest stamp over recordGeneration's.
        AtomicReference<LocalDateTime> stampAtPasswordSave = new AtomicReference<>();
        when(reportDocumentService.ensureReportPassword(any())).thenAnswer(inv -> {
            Assessment a = inv.getArgument(0);
            stampAtPasswordSave.set(a.getRetestReportGeneratedAt());
            return "pw";
        });
        when(reportEncryptor.encrypt(any(), eq("pw"))).thenReturn("encrypted".getBytes());

        service.generateRetestReport("asmt-1", "user-1");

        ArgumentCaptor<LocalDateTime> started = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retestReportService).recordGeneration(eq("asmt-1"), started.capture(), any());
        verify(reportDocumentService).ensureReportPassword(baseAssessment);
        assertThat(stampAtPasswordSave.get()).isEqualTo(started.getValue());
    }

    @Test
    void nothingIsRecordedWhenRenderingFails() {
        when(storageService.downloadBytes(RETEST_TEMPLATE_KEY)).thenThrow(new RuntimeException("MinIO down"));

        assertThatThrownBy(() -> service.generateRetestReport("asmt-1", "user-1"))
                .hasMessageContaining("MinIO down");

        verify(retestReportService, never()).recordGeneration(any(), any(), any());
        verify(reportDocumentService, never()).markCompleted(any(), any(), any());
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsATemplateWithoutARetestDocx() {
        template.setRetestTemplateFileId(null);

        assertThatThrownBy(() -> service.generateRetestReport("asmt-1", "user-1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("retest template");
        verify(storageService, never()).downloadBytes(anyString());
    }

    @Test
    void anAssessmentOnADeletedTemplateUsesTheTypesCurrentRetestDocx() throws Exception {
        // The template this assessment was created from was retired; the retest DOCX was
        // uploaded to its replacement, which is what the report must be built from.
        baseAssessment.setAssessmentTypeId("type-ext");
        template.setDeletedAt(LocalDateTime.now());
        template.setRetestTemplateFileId(null);
        String currentKey = "report-templates/tmpl-2/retest/current.docx";
        when(reportTemplateRepository.findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc("type-ext"))
                .thenReturn(Optional.of(ReportTemplate.builder()
                        .id("tmpl-2").active(true).assessmentTypeId("type-ext")
                        .templateFileId("report-templates/tmpl-2/report.docx")
                        .retestTemplateFileId(currentKey).build()));
        when(storageService.downloadBytes(currentKey)).thenReturn(retestTemplate());

        service.generateRetestReport("asmt-1", "user-1");

        verify(storageService).downloadBytes(currentKey);
        verify(reportDocumentService).markCompleted(eq("asmt-1"), eq(ReportDocumentType.RETEST_DOCX), anyString());
    }

    @Test
    void rejectsAnAssessmentWithNoReportTemplate() {
        baseAssessment.setReportTemplateId(null);

        assertThatThrownBy(() -> service.generateRetestReport("asmt-1", "user-1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("retest template");
    }

    @Test
    void notifiesTheGeneratingUser() {
        service.generateRetestReport("asmt-1", "user-1");

        verify(notificationService).send(eq("user-1"), eq("Retest report ready"), contains("Test Assessment"),
                eq("RETEST_REPORT"), eq("/assessments/asmt-1"));
    }

    @Test
    void aNotificationFailureDoesNotFailTheRun() {
        doAnswer(inv -> { throw new RuntimeException("SSE down"); })
                .when(notificationService).send(any(), any(), any(), any(), any());

        service.generateRetestReport("asmt-1", "user-1");

        verify(retestReportService).recordGeneration(eq("asmt-1"), any(), any());
    }

    @Test
    void doesNotTouchTheMainReportFields() {
        service.generateRetestReport("asmt-1", "user-1");

        assertThat(baseAssessment.getGeneratedReportFileId()).isEqualTo("reports/asmt-1/report-1.docx");
        assertThat(baseAssessment.getReportGeneratedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        verify(assessmentRepository, never()).save(any());
    }
}
