package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deleted report template must not be offered for anything new.
 *
 * <p>Deletion is soft so assessments already generated from a template keep resolving it.
 * The listing endpoints did not account for that, so a deleted template still appeared in
 * {@code GET /report-templates} — which is how the vulnerability import script picked one
 * and then failed on every row, with an error that said "not active" rather than "deleted".
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportTemplateDeletionTest extends TestContainersConfig {

    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;

    private String typeId;
    private String liveId;
    private String deletedId;

    @BeforeEach
    void setUp() {
        typeId = assessmentTypeRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("bootstrap seeds assessment types"))
                .getId();

        liveId = reportTemplateRepository.save(ReportTemplate.builder()
                .name("Live Template " + System.nanoTime())
                .assessmentTypeId(typeId)
                .active(true)
                .build()).getId();

        // Deleted but still flagged active — the combination the old code would have accepted.
        deletedId = reportTemplateRepository.save(ReportTemplate.builder()
                .name("Deleted Template " + System.nanoTime())
                .assessmentTypeId(typeId)
                .active(true)
                .deletedAt(LocalDateTime.now())
                .build()).getId();
    }

    @Test
    void listingsExcludeDeletedTemplates() {
        assertThat(reportTemplateRepository.findByActiveTrueAndDeletedAtIsNull(Pageable.unpaged()))
                .extracting(ReportTemplate::getId).contains(liveId).doesNotContain(deletedId);

        assertThat(reportTemplateRepository.findByDeletedAtIsNull(Pageable.unpaged()))
                .extracting(ReportTemplate::getId).contains(liveId).doesNotContain(deletedId);

        assertThat(reportTemplateRepository
                .findByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNull(typeId, Pageable.unpaged()))
                .extracting(ReportTemplate::getId).contains(liveId).doesNotContain(deletedId);
    }

    /**
     * The sharper half of the bug: an unfiltered findById plus an active-only check would
     * have accepted this template, creating assessments against one nobody can see.
     */
    @Test
    void lookupRefusesADeletedTemplateEvenWhileItIsStillFlaggedActive() {
        assertThat(reportTemplateRepository.findByIdAndDeletedAtIsNull(deletedId)).isEmpty();
        assertThat(reportTemplateRepository.findByIdAndDeletedAtIsNull(liveId)).isPresent();
        assertThat(reportTemplateRepository.existsById(deletedId))
                .as("still on disk — deletion is soft, so existing assessments keep resolving it")
                .isTrue();
    }
}
