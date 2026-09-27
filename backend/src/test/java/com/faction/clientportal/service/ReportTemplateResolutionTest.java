package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which template a report is generated from: the assessment's own while it is live, otherwise
 * the newest live template for the assessment's type. Deleting a template must not strand every
 * assessment created from it on a template nobody can open or update any more.
 */
class ReportTemplateResolutionTest {

    private final ReportTemplateRepository repo = mock(ReportTemplateRepository.class);

    private final Assessment assessment = Assessment.builder()
            .id("asmt-1").assessmentTypeId("type-ext").reportTemplateId("own").build();

    private final ReportTemplate own = ReportTemplate.builder()
            .id("own").assessmentTypeId("type-ext").active(true).build();

    private final ReportTemplate newest = ReportTemplate.builder()
            .id("newest").assessmentTypeId("type-ext").active(true)
            .retestTemplateFileId("report-templates/newest/retest/r.docx").build();

    @Test
    void usesTheAssessmentsOwnTemplateWhileItIsLive() {
        when(repo.findById("own")).thenReturn(Optional.of(own));
        when(repo.findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc("type-ext"))
                .thenReturn(Optional.of(newest));

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).contains(own);
    }

    @Test
    void aDeletedOwnTemplateFallsBackToTheNewestLiveTemplateForTheType() {
        own.setDeletedAt(LocalDateTime.now());
        when(repo.findById("own")).thenReturn(Optional.of(own));
        when(repo.findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc("type-ext"))
                .thenReturn(Optional.of(newest));

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).contains(newest);
    }

    @Test
    void aDeactivatedOwnTemplateAlsoFallsBack() {
        own.setActive(false);
        when(repo.findById("own")).thenReturn(Optional.of(own));
        when(repo.findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc("type-ext"))
                .thenReturn(Optional.of(newest));

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).contains(newest);
    }

    @Test
    void aDeletedOwnTemplateIsStillUsedWhenTheTypeHasNoLiveTemplate() {
        // Better the retired template than no report at all.
        own.setDeletedAt(LocalDateTime.now());
        when(repo.findById("own")).thenReturn(Optional.of(own));

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).contains(own);
    }

    @Test
    void anAssessmentWithNoTemplateUsesTheNewestForItsType() {
        assessment.setReportTemplateId(null);
        when(repo.findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc("type-ext"))
                .thenReturn(Optional.of(newest));

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).contains(newest);
    }

    @Test
    void nothingToResolveIsEmpty() {
        assessment.setReportTemplateId(null);
        assessment.setAssessmentTypeId(null);

        assertThat(ReportTemplateResolution.forReport(repo, assessment)).isEmpty();
    }
}
