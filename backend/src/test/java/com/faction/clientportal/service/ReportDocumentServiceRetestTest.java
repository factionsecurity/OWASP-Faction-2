package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportDocumentsDto;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentStatus;
import com.faction.clientportal.model.ReportDocumentType;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportDocumentRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers how {@link ReportDocumentService} keeps the retest report's document types apart from
 * the main report's — the main-report Finalize panel treats any GENERATING doc as "still
 * running", so a retest run must never touch the main report's rows and vice versa.
 */
@ExtendWith(MockitoExtension.class)
class ReportDocumentServiceRetestTest {

    @Mock private ReportDocumentRepository repo;
    @Mock private AssessmentRepository     assessmentRepository;
    @Mock private EncryptionService        encryptionService;
    @Mock private ReportEncryptor          reportEncryptor;

    // These suites describe edition-gated behaviour, so they run under the real
    // enterprise policy rather than a mock — a bare mock reports every feature as
    // off, which would quietly skip the encrypted-PDF cases these tests are about.
    @Spy private com.faction.clientportal.edition.EditionPolicy editionPolicy =
            new com.faction.clientportal.edition.UnrestrictedEditionPolicy();

    @InjectMocks
    private ReportDocumentService service;

    @Test
    void startGenerationWithoutTypesMarksOnlyTheMainReport() {
        when(repo.findByAssessmentIdAndDocType(eq("a1"), any())).thenReturn(Optional.empty());
        service.startGeneration("a1");
        ArgumentCaptor<ReportDocument> saved = ArgumentCaptor.forClass(ReportDocument.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ReportDocument::getDocType)
                .containsExactlyInAnyOrder(ReportDocumentType.DOCX, ReportDocumentType.PDF, ReportDocumentType.ENCRYPTED_PDF);
    }

    @Test
    void getDocumentsSplitsMainAndRetestDocuments() {
        when(repo.findByAssessmentId("a1")).thenReturn(List.of(
                doc(ReportDocumentType.DOCX), doc(ReportDocumentType.RETEST_DOCX), doc(ReportDocumentType.RETEST_PDF)));
        ReportDocumentsDto dto = service.getDocuments(Assessment.builder().id("a1").build());
        assertThat(dto.getDocuments()).extracting("type").containsExactly(ReportDocumentType.DOCX);
        assertThat(dto.getRetestDocuments()).extracting("type")
                .containsExactly(ReportDocumentType.RETEST_DOCX, ReportDocumentType.RETEST_PDF);
    }

    @Test
    void failStuckDocumentsForRetestTypesLeavesTheMainReportAlone() {
        ReportDocument main = doc(ReportDocumentType.DOCX);
        ReportDocument retest = doc(ReportDocumentType.RETEST_DOCX);
        main.setStatus(ReportDocumentStatus.GENERATING);
        retest.setStatus(ReportDocumentStatus.GENERATING);
        when(repo.findByAssessmentId("a1")).thenReturn(List.of(main, retest));
        service.failStuckDocuments("a1", ReportDocumentType.RETEST, "boom");
        assertThat(main.getStatus()).isEqualTo(ReportDocumentStatus.GENERATING);
        assertThat(retest.getStatus()).isEqualTo(ReportDocumentStatus.FAILED);
    }

    @Test
    void theRetestEncryptedPdfFollowsTheEncryptedPdfEditionGate() {
        org.mockito.Mockito.doReturn(false).when(editionPolicy)
                .enabled(com.faction.clientportal.edition.Feature.ENCRYPTED_PDF);
        when(repo.findByAssessmentIdAndDocType(eq("a1"), any())).thenReturn(Optional.empty());

        service.startGeneration("a1", ReportDocumentType.RETEST);

        verify(repo, never()).save(org.mockito.ArgumentMatchers.argThat(
                d -> d.getDocType() == ReportDocumentType.RETEST_ENCRYPTED_PDF));
    }

    private ReportDocument doc(ReportDocumentType t) {
        return ReportDocument.builder().assessmentId("a1").docType(t)
                .status(ReportDocumentStatus.COMPLETED).fileId("k-" + t).build();
    }
}
