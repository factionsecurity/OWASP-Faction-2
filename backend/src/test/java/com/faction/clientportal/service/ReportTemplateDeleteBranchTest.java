package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deleting a report template either soft-deletes it or destroys it permanently, depending on
 * whether any assessment still uses it — and that branch had no test.
 *
 * <p>It is the branch that matters most in the whole class. Take the wrong one and you either
 * shred the template a finalized report was written against, along with its DOCX in storage, or
 * you leave every template ever created lying around forever. {@code ReportTemplateDeletionTest}
 * looks like cover for this but never calls the method: it hand-sets {@code deletedAt} and asserts
 * that listings filter it out.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportTemplateDeleteBranchTest extends TestContainersConfig {

    @Autowired private ReportTemplateService reportTemplateService;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private AssessmentRepository assessmentRepository;

    /** Mocked so the hard-delete branch's storage call can be observed rather than performed. */
    @MockBean private StorageService storageService;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
    }

    @Test
    void aTemplateNoAssessmentUsesIsDestroyedOutright() {
        String id = saveTemplate("Unused", "stored-file-1").getId();

        Map<String, String> result = reportTemplateService.deleteReportTemplate(id);

        assertThat(result.get("status")).isEqualTo("deleted");
        assertThat(reportTemplateRepository.findById(id)).isEmpty();
        // The DOCX goes with it — otherwise storage accumulates a file nothing can reach.
        verify(storageService).deleteObject("stored-file-1");
    }

    @Test
    void aTemplateAnAssessmentUsesIsOnlyDeactivated() {
        ReportTemplate template = saveTemplate("In use", "stored-file-2");
        assessmentRepository.save(assessmentUsing(template.getId()));

        Map<String, String> result = reportTemplateService.deleteReportTemplate(template.getId());

        assertThat(result.get("status")).isEqualTo("deactivated");
        ReportTemplate kept = reportTemplateRepository.findById(template.getId()).orElseThrow();
        assertThat(kept.getDeletedAt()).isNotNull();
        assertThat(kept.getActive()).isFalse();
        // A report was written against this template. Destroying it, or its file, would make that
        // report unreproducible.
        verify(storageService, never()).deleteObject("stored-file-2");
    }

    @Test
    void anAssessmentThatWasItselfDeletedNoLongerProtectsTheTemplate() {
        ReportTemplate template = saveTemplate("Freed", "stored-file-3");
        Assessment assessment = assessmentRepository.save(assessmentUsing(template.getId()));
        assessment.setDeletedAt(LocalDateTime.now());
        assessmentRepository.save(assessment);

        // The check is existsBy…AndDeletedAtIsNull, so a soft-deleted assessment does not count.
        assertThat(reportTemplateService.deleteReportTemplate(template.getId()).get("status"))
                .isEqualTo("deleted");
        assertThat(reportTemplateRepository.findById(template.getId())).isEmpty();
    }

    @Test
    void aTemplateWithNoFileIsStillDeletedCleanly() {
        String id = saveTemplate("No file", null).getId();

        assertThat(reportTemplateService.deleteReportTemplate(id).get("status")).isEqualTo("deleted");

        assertThat(reportTemplateRepository.findById(id)).isEmpty();
        verify(storageService, never()).deleteObject(null);
    }

    @Test
    void deletingATemplateThatIsNotThereIs404() {
        assertThatThrownBy(() -> reportTemplateService.deleteReportTemplate("ghost"))
                .isInstanceOf(com.faction.clientportal.exception.ResourceNotFoundException.class);
    }

    private ReportTemplate saveTemplate(String name, String fileId) {
        return reportTemplateRepository.save(ReportTemplate.builder()
                .name(name).description("d").assessmentTypeId("type-1")
                .version(1).active(true).templateFileId(fileId)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now()).build());
    }

    private Assessment assessmentUsing(String templateId) {
        return Assessment.builder()
                .name("Uses the template").applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("type-1").status("IN_PROGRESS")
                .reportTemplateId(templateId)
                .createdAt(LocalDateTime.now()).build();
    }
}
