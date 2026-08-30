package com.faction.clientportal.controller.v1;

import com.faction.clientportal.edition.CommunityOnly;
import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.util.StoredObjects;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentStatus;
import com.faction.clientportal.model.ReportDocumentType;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.ReportGenerationTrigger;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.LibreOfficeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        // 32-byte base64 key so EncryptionService can round-trip the report password
        "sso.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest extends TestContainersConfig {

    @Autowired private MockMvc                  mockMvc;
    @Autowired private AssessmentRepository     assessmentRepository;
    @Autowired private UserRepository           userRepository;
    @Autowired private RoleRepository           roleRepository;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private JwtService               jwtService;
    @Autowired private ReportDocumentRepository reportDocumentRepository;
    @Autowired private com.faction.clientportal.service.EncryptionService encryptionService;

    // Mock heavy dependencies so they don't try to connect to MinIO/docx4j/LibreOffice
    @MockBean private ReportGenerationTrigger reportGenerationTrigger;
    @MockBean private StorageService          storageService;
    @MockBean private LibreOfficeConverter    libreOfficeConverter;

    private String jwtToken;
    private Assessment testAssessment;

    @BeforeEach
    void setUp() {
        reportDocumentRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role superAdminRole = roleRepository.save(
                Role.builder()
                    .name("SuperAdmin")
                    .permissions(List.of("super_admin"))
                    .build());

        User testUser = userRepository.save(
                User.builder()
                    .username("report-test-user")
                    .email("report@test.com")
                    .password(passwordEncoder.encode("password"))
                    .firstName("Report")
                    .lastName("Tester")
                    .loginOption(LoginOption.NATIVE)
                    .roleIds(List.of(superAdminRole.getId()))
                    .isInternal(true)
                    .createdAt(LocalDateTime.now())
                    .build());

        jwtToken = jwtService.generateToken(
                testUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        testAssessment = assessmentRepository.save(
                Assessment.builder()
                    .name("Test Assessment")
                    .applicationId("app-1")
                    .assessmentTypeId("type-1")
                    .organizationId("org-1")
                    .templateFileId("templates/tmpl-1/template.docx")
                    .status("IN_PROGRESS")
                    .createdAt(LocalDateTime.now())
                    .build());
    }

    // ── POST /{assessmentId}/generate ────────────────────────────────────────

    @Test
    void generateReport_returnsForbiddenWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/reports/{id}/generate", testAssessment.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void generateReport_returns202WhenAssessmentExists() throws Exception {
        doNothing().when(reportGenerationTrigger).trigger(anyString(), anyString());

        mockMvc.perform(post("/api/v1/reports/{id}/generate", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("started")));

        verify(reportGenerationTrigger).trigger(eq(testAssessment.getId()), anyString());
    }

    @Test
    void generateReport_returns404ForMissingAssessment() throws Exception {
        mockMvc.perform(post("/api/v1/reports/{id}/generate", "non-existent-id")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void generateReport_returns409WhenTheAssessmentIsCompleted() throws Exception {
        completeTestAssessment();

        // The report of a completed assessment is the issued deliverable — regenerating it would
        // silently change what was already delivered.
        mockMvc.perform(post("/api/v1/reports/{id}/generate", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isConflict());

        verify(reportGenerationTrigger, never()).trigger(anyString(), anyString());
    }

    // ── POST /{assessmentId}/upload ──────────────────────────────────────────

    @Test
    void uploadReport_returns409WhenTheAssessmentIsCompleted() throws Exception {
        completeTestAssessment();

        var docx = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "PK-docx".getBytes());

        mockMvc.perform(multipart("/api/v1/reports/{id}/upload", testAssessment.getId()).file(docx)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isConflict());
    }

    @Test
    void downloadStillWorksOnACompletedAssessment() throws Exception {
        completeTestAssessment();

        // Blocking regeneration must not block reading the report that was issued. 404 here is the
        // "no report generated" path, not a rejection of the completed state.
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "DOCX")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    /** Move the fixture assessment into a completed state. */
    private void completeTestAssessment() {
        testAssessment.setStatus("COMPLETED");
        testAssessment.setCompletedDate(LocalDateTime.now());
        assessmentRepository.save(testAssessment);
    }

    // ── GET /{assessmentId}/documents/{type}/content ─────────────────────────

    @Test
    void downloadReport_returnsForbiddenWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "DOCX"))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadReport_returns404WhenNoReportGenerated() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "DOCX")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadReport_streamsBytesWhenReportExists() throws Exception {
        testAssessment.setGeneratedReportFileId("reports/asmt-1/report-123.docx");
        assessmentRepository.save(testAssessment);

        when(storageService.openStream(anyString()))
                .thenReturn(StoredObjects.of("docx-bytes"));

        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "DOCX")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().string("docx-bytes"))
                // Never rendered in the browser: these are user-supplied files
                // served from the app's own origin.
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void downloadReport_returns404ForMissingAssessment() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", "non-existent-id", "DOCX")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    // ── GET /{assessmentId}/pdf ───────────────────────────────────────────────

    @Test
    void getReportAsPdf_returnsForbiddenWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/pdf", testAssessment.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReportAsPdf_returns404WhenNoReportGenerated() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/pdf", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReportAsPdf_returns404ForMissingAssessment() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/pdf", "non-existent-id")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReportAsPdf_returnsPdfBytesWhenReportExists() throws Exception {
        testAssessment.setGeneratedReportFileId("reports/asmt-1/report-123.docx");
        assessmentRepository.save(testAssessment);

        byte[] fakePdf = "%PDF-1.4 fake pdf content".getBytes();
        when(storageService.downloadBytes(anyString())).thenReturn("fake docx".getBytes());
        when(libreOfficeConverter.convertToPdf(any(byte[].class))).thenReturn(fakePdf);

        mockMvc.perform(get("/api/v1/reports/{id}/pdf", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(fakePdf));
    }

    // ── per-document tracking ────────────────────────────────────────────────

    /**
     * The community counterpart, and a direct guard on a real regression: the panel treats
     * any document still GENERATING as the whole run still going, so an encrypted variant
     * marked generating and then never produced left the spinner running and Preview and
     * Download disabled behind it.
     */
    @Test
    @CommunityOnly
    void generateReport_marksOnlyTheTypesThisBuildCanProduce() throws Exception {
        doNothing().when(reportGenerationTrigger).trigger(anyString(), anyString());

        mockMvc.perform(post("/api/v1/reports/{id}/generate", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/reports/{id}/documents", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents.length()").value(2))
                .andExpect(jsonPath("$.data.documents[?(@.type == 'ENCRYPTED_PDF')]").isEmpty());
    }

    @Test
    @EnterpriseOnly
    void generateReport_marksAllDocumentTypesGenerating() throws Exception {
        doNothing().when(reportGenerationTrigger).trigger(anyString(), anyString());

        mockMvc.perform(post("/api/v1/reports/{id}/generate", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isAccepted());

        var docs = reportDocumentRepository.findByAssessmentId(testAssessment.getId());
        org.assertj.core.api.Assertions.assertThat(docs)
                .hasSize(3)
                .allSatisfy(d -> org.assertj.core.api.Assertions.assertThat(d.getStatus())
                        .isEqualTo(ReportDocumentStatus.GENERATING));
    }

    @Test
    @EnterpriseOnly
    void getReportDocuments_returnsPerDocumentStatuses() throws Exception {
        reportDocumentRepository.save(ReportDocument.builder()
                .assessmentId(testAssessment.getId())
                .docType(ReportDocumentType.DOCX)
                .status(ReportDocumentStatus.COMPLETED)
                .fileId("reports/a/report-1.docx")
                .generatedAt(LocalDateTime.now())
                .build());
        reportDocumentRepository.save(ReportDocument.builder()
                .assessmentId(testAssessment.getId())
                .docType(ReportDocumentType.PDF)
                .status(ReportDocumentStatus.GENERATING)
                .build());
        reportDocumentRepository.save(ReportDocument.builder()
                .assessmentId(testAssessment.getId())
                .docType(ReportDocumentType.ENCRYPTED_PDF)
                .status(ReportDocumentStatus.FAILED)
                .errorMessage("Skipped — PDF conversion failed")
                .build());

        mockMvc.perform(get("/api/v1/reports/{id}/documents", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents.length()").value(3))
                .andExpect(jsonPath("$.data.documents[0].type").value("DOCX"))
                .andExpect(jsonPath("$.data.documents[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.documents[0].available").value(true))
                .andExpect(jsonPath("$.data.documents[1].type").value("PDF"))
                .andExpect(jsonPath("$.data.documents[1].status").value("GENERATING"))
                .andExpect(jsonPath("$.data.documents[2].type").value("ENCRYPTED_PDF"))
                .andExpect(jsonPath("$.data.documents[2].status").value("FAILED"))
                .andExpect(jsonPath("$.data.documents[2].errorMessage").value(
                        "Skipped — PDF conversion failed"));
    }

    @Test
    @EnterpriseOnly
    void getReportDocuments_includesDecryptedReportPassword() throws Exception {
        testAssessment.setReportPasswordEncrypted(encryptionService.encrypt("abcd1234efgh5678"));
        assessmentRepository.save(testAssessment);

        mockMvc.perform(get("/api/v1/reports/{id}/documents", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportPassword").value("abcd1234efgh5678"));
    }

    @Test
    void getReportDocuments_fallsBackToLegacyDocxReport() throws Exception {
        testAssessment.setGeneratedReportFileId("reports/asmt-1/report-123.docx");
        testAssessment.setReportGeneratedAt(LocalDateTime.now());
        assessmentRepository.save(testAssessment);

        mockMvc.perform(get("/api/v1/reports/{id}/documents", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents.length()").value(1))
                .andExpect(jsonPath("$.data.documents[0].type").value("DOCX"))
                .andExpect(jsonPath("$.data.documents[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.documents[0].available").value(true));
    }

    @Test
    void downloadReport_streamsBytesForTypedDocument() throws Exception {
        reportDocumentRepository.save(ReportDocument.builder()
                .assessmentId(testAssessment.getId())
                .docType(ReportDocumentType.ENCRYPTED_PDF)
                .status(ReportDocumentStatus.COMPLETED)
                .fileId("reports/a/report-1-encrypted.pdf")
                .generatedAt(LocalDateTime.now())
                .build());

        when(storageService.openStream("reports/a/report-1-encrypted.pdf"))
                .thenReturn(StoredObjects.of("encrypted-pdf-bytes"));

        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "encrypted-pdf")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().string("encrypted-pdf-bytes"));
    }

    @Test
    void downloadReport_returns404WhenTypedDocumentMissing() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content", testAssessment.getId(), "pdf")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadReport_returns400ForUnknownType() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{id}/documents/{type}/content",
                        testAssessment.getId(), "carrier-pigeon")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());
    }

    // ── POST /{assessmentId}/upload ──────────────────────────────────────────

    @Test
    void uploadReport_returnsForbiddenWithoutToken() throws Exception {
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "docx bytes".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", testAssessment.getId())
                        .file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadReport_returns404ForMissingAssessment() throws Exception {
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "docx bytes".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", "non-existent-id")
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadReport_returns400ForUnsupportedContentType() throws Exception {
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.txt", "text/plain", "not a report".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", testAssessment.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadReport_returns400ForEmptyFile() throws Exception {
        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", testAssessment.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @EnterpriseOnly
    void uploadReport_docxUpload_marksAllThreeDocumentTypesGenerating() throws Exception {
        doNothing().when(reportGenerationTrigger)
                .triggerUpload(anyString(), anyString(), any(byte[].class), any(ReportDocumentType.class));

        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "docx bytes".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", testAssessment.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isAccepted());

        var docs = reportDocumentRepository.findByAssessmentId(testAssessment.getId());
        org.assertj.core.api.Assertions.assertThat(docs)
                .hasSize(3)
                .allSatisfy(d -> org.assertj.core.api.Assertions.assertThat(d.getStatus())
                        .isEqualTo(ReportDocumentStatus.GENERATING));
        verify(reportGenerationTrigger).triggerUpload(
                eq(testAssessment.getId()), anyString(), any(byte[].class), eq(ReportDocumentType.DOCX));
    }

    @Test
    @EnterpriseOnly
    void uploadReport_pdfUpload_onlyMarksPdfAndEncryptedPdfGenerating() throws Exception {
        doNothing().when(reportGenerationTrigger)
                .triggerUpload(anyString(), anyString(), any(byte[].class), any(ReportDocumentType.class));

        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf bytes".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/reports/{id}/upload", testAssessment.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isAccepted());

        var docs = reportDocumentRepository.findByAssessmentId(testAssessment.getId());
        org.assertj.core.api.Assertions.assertThat(docs)
                .hasSize(2)
                .extracting(ReportDocument::getDocType)
                .containsExactlyInAnyOrder(ReportDocumentType.PDF, ReportDocumentType.ENCRYPTED_PDF);
        verify(reportGenerationTrigger).triggerUpload(
                eq(testAssessment.getId()), anyString(), any(byte[].class), eq(ReportDocumentType.PDF));
    }

    @Test
    void getReportAsPdf_returns500WhenConversionFails() throws Exception {
        testAssessment.setGeneratedReportFileId("reports/asmt-1/report-123.docx");
        assessmentRepository.save(testAssessment);

        when(storageService.downloadBytes(anyString())).thenReturn("fake docx".getBytes());
        when(libreOfficeConverter.convertToPdf(any(byte[].class)))
                .thenThrow(new java.io.IOException("soffice not found"));

        mockMvc.perform(get("/api/v1/reports/{id}/pdf", testAssessment.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isInternalServerError());
    }
}
