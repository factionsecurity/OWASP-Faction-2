package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportDocumentDto;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.edition.UnrestrictedEditionPolicy;
import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentStatus;
import com.faction.clientportal.model.ReportDocumentType;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportDocumentRepository;
import com.faction.clientportal.service.ReportEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportDocumentServiceTest {

    @Mock private ReportDocumentRepository reportDocumentRepository;
    @Mock private AssessmentRepository     assessmentRepository;
    @Mock private EncryptionService        encryptionService;

    // These suites describe enterprise behaviour, so they run under the real
    // enterprise policy rather than a mock — a bare mock reports every feature as
    // off, which would quietly skip the encryption these tests are about.
    @Spy private com.faction.clientportal.edition.EditionPolicy editionPolicy =
            new com.faction.clientportal.edition.UnrestrictedEditionPolicy();
    @Mock private ReportEncryptor          reportEncryptor;

    @InjectMocks
    private ReportDocumentService service;

    @Test
    void startGeneration_marksAllThreeDocumentTypesGenerating() {
        when(reportDocumentRepository.findByAssessmentIdAndDocType(anyString(), any()))
                .thenReturn(Optional.empty());

        service.startGeneration("asmt-1");

        ArgumentCaptor<ReportDocument> captor = ArgumentCaptor.forClass(ReportDocument.class);
        verify(reportDocumentRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReportDocument::getDocType)
                .containsExactlyInAnyOrder(ReportDocumentType.values());
        assertThat(captor.getAllValues())
                .allSatisfy(doc -> {
                    assertThat(doc.getStatus()).isEqualTo(ReportDocumentStatus.GENERATING);
                    assertThat(doc.getErrorMessage()).isNull();
                });
    }

    @Test
    void startGeneration_keepsPreviousFileWhileRegenerating() {
        ReportDocument existing = ReportDocument.builder()
                .assessmentId("asmt-1")
                .docType(ReportDocumentType.DOCX)
                .status(ReportDocumentStatus.COMPLETED)
                .fileId("reports/asmt-1/report-1.docx")
                .build();
        when(reportDocumentRepository.findByAssessmentIdAndDocType("asmt-1", ReportDocumentType.DOCX))
                .thenReturn(Optional.of(existing));
        when(reportDocumentRepository.findByAssessmentIdAndDocType(eq("asmt-1"),
                argThat(t -> t != ReportDocumentType.DOCX)))
                .thenReturn(Optional.empty());

        service.startGeneration("asmt-1");

        assertThat(existing.getStatus()).isEqualTo(ReportDocumentStatus.GENERATING);
        assertThat(existing.getFileId()).isEqualTo("reports/asmt-1/report-1.docx");
    }

    @Test
    void startGeneration_withTypeSubset_onlyMarksGivenTypes() {
        when(reportDocumentRepository.findByAssessmentIdAndDocType(anyString(), any()))
                .thenReturn(Optional.empty());

        service.startGeneration("asmt-1",
                java.util.EnumSet.of(ReportDocumentType.PDF, ReportDocumentType.ENCRYPTED_PDF));

        ArgumentCaptor<ReportDocument> captor = ArgumentCaptor.forClass(ReportDocument.class);
        verify(reportDocumentRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReportDocument::getDocType)
                .containsExactlyInAnyOrder(ReportDocumentType.PDF, ReportDocumentType.ENCRYPTED_PDF);
    }

    @Test
    void failStuckDocuments_onlyFailsGeneratingOnes() {
        ReportDocument generating = ReportDocument.builder()
                .assessmentId("asmt-1").docType(ReportDocumentType.PDF)
                .status(ReportDocumentStatus.GENERATING).build();
        ReportDocument completed = ReportDocument.builder()
                .assessmentId("asmt-1").docType(ReportDocumentType.DOCX)
                .status(ReportDocumentStatus.COMPLETED).build();
        when(reportDocumentRepository.findByAssessmentId("asmt-1"))
                .thenReturn(List.of(generating, completed));

        service.failStuckDocuments("asmt-1", "boom");

        verify(reportDocumentRepository, times(1)).save(generating);
        verify(reportDocumentRepository, never()).save(completed);
        assertThat(generating.getStatus()).isEqualTo(ReportDocumentStatus.FAILED);
        assertThat(generating.getErrorMessage()).isEqualTo("boom");
        assertThat(completed.getStatus()).isEqualTo(ReportDocumentStatus.COMPLETED);
    }

    @Test
    void findFileId_fallsBackToLegacyDocxField() {
        Assessment assessment = Assessment.builder()
                .id("asmt-1")
                .generatedReportFileId("reports/asmt-1/legacy.docx")
                .build();
        when(reportDocumentRepository.findByAssessmentIdAndDocType("asmt-1", ReportDocumentType.DOCX))
                .thenReturn(Optional.empty());
        when(reportDocumentRepository.findByAssessmentIdAndDocType("asmt-1", ReportDocumentType.PDF))
                .thenReturn(Optional.empty());

        assertThat(service.findFileId(assessment, ReportDocumentType.DOCX))
                .contains("reports/asmt-1/legacy.docx");
        assertThat(service.findFileId(assessment, ReportDocumentType.PDF)).isEmpty();
    }

    @Test
    void ensureReportPassword_generatesAndStoresEncryptedOnFirstUse() {
        Assessment assessment = Assessment.builder().id("asmt-1").build();
        when(encryptionService.isConfigured()).thenReturn(true);
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        // What the generated password looks like is the encryptor's business, and the
        // encryptor is in the overlay — PdfEncryptorTest covers its format. What matters
        // here is that this service stores it encrypted and hands back the plaintext.
        when(reportEncryptor.generatePassword(anyInt())).thenReturn("s3cr3tpassw0rd12");

        String password = service.ensureReportPassword(assessment);

        assertThat(password).isEqualTo("s3cr3tpassw0rd12");
        assertThat(assessment.getReportPasswordEncrypted()).isEqualTo("enc:s3cr3tpassw0rd12");
        verify(assessmentRepository).save(assessment);
    }

    @Test
    void ensureReportPassword_reusesExistingPassword() {
        Assessment assessment = Assessment.builder()
                .id("asmt-1")
                .reportPasswordEncrypted("enc:existing")
                .build();
        when(encryptionService.decrypt("enc:existing")).thenReturn("existing");

        assertThat(service.ensureReportPassword(assessment)).isEqualTo("existing");
        verify(assessmentRepository, never()).save(any());
    }

    /**
     * The bug this guards against: the Finalize panel treats any document still GENERATING
     * as the whole run still going. Marking the encrypted variant generating and then
     * skipping it left the spinner running forever, with Preview and Download disabled
     * behind it — a report that had in fact finished looked permanently stuck.
     */
    @Test
    void startGeneration_skipsTheEncryptedVariantWhenItCannotBeProduced() {
        org.mockito.Mockito.doReturn(false).when(editionPolicy)
                .enabled(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);
        when(reportDocumentRepository.findByAssessmentIdAndDocType(anyString(), any()))
                .thenReturn(java.util.Optional.empty());

        service.startGeneration("asmt-1");

        verify(reportDocumentRepository, never()).save(argThat(
                d -> d.getDocType() == ReportDocumentType.ENCRYPTED_PDF));
    }

    /** Rows left behind by a build that could once produce them are not reported either. */
    @Test
    void getDocuments_omitsAnEncryptedVariantLeftOverFromAnotherBuild() {
        org.mockito.Mockito.doReturn(false).when(editionPolicy)
                .enabled(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);
        when(reportDocumentRepository.findByAssessmentId("asmt-1")).thenReturn(java.util.List.of(
                ReportDocument.builder().assessmentId("asmt-1").docType(ReportDocumentType.PDF)
                        .status(ReportDocumentStatus.COMPLETED).fileId("k").build(),
                ReportDocument.builder().assessmentId("asmt-1").docType(ReportDocumentType.ENCRYPTED_PDF)
                        .status(ReportDocumentStatus.GENERATING).build()));

        var dto = service.getDocuments(Assessment.builder().id("asmt-1").build());

        assertThat(dto.getDocuments()).extracting(ReportDocumentDto::getType)
                .containsExactly(ReportDocumentType.PDF);
        assertThat(dto.getDocuments()).noneMatch(d -> d.getStatus() == ReportDocumentStatus.GENERATING);
    }

    /**
     * A database that once ran the paid edition still holds encrypted report passwords.
     * Withholding them is the point: "no password stored" and "not your password to see"
     * look identical to the caller, and only the second is true after a downgrade.
     */
    @Test
    void getDocuments_withholdsAStoredPasswordWhenEncryptionIsUnavailable() {
        Assessment assessment = Assessment.builder()
                .id("asmt-1")
                .reportPasswordEncrypted("enc:leftover")
                .build();
        org.mockito.Mockito.doReturn(false).when(editionPolicy)
                .enabled(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);

        assertThat(service.getDocuments(assessment).getReportPassword()).isNull();
        verify(encryptionService, never()).decrypt(anyString());
    }

    /**
     * The open source edition never mints a report password. This is the choke point the
     * encrypted variant depends on, so refusing here is what stops one being produced by
     * either generation or a direct upload.
     */
    @Test
    void ensureReportPassword_isRefusedInTheCommunityEdition() {
        Assessment assessment = Assessment.builder().id("asmt-1").build();
        org.mockito.Mockito.doThrow(new com.faction.clientportal.edition.FeatureNotLicensedException(
                        com.faction.clientportal.edition.Feature.ENCRYPTED_PDF))
                .when(editionPolicy)
                .require(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);

        assertThatThrownBy(() -> service.ensureReportPassword(assessment))
                .isInstanceOf(com.faction.clientportal.edition.FeatureNotLicensedException.class);

        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void ensureReportPassword_throwsWhenEncryptionKeyMissing() {
        Assessment assessment = Assessment.builder().id("asmt-1").build();
        when(encryptionService.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.ensureReportPassword(assessment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSO_ENCRYPTION_KEY");
    }
}
