package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateReportTemplateRequest;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.edition.FeatureNotLicensedException;
import com.faction.clientportal.edition.UnrestrictedEditionPolicy;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Report sections are a paid feature, gated where they come into being: on the template.
 * Everything downstream — the assessment's section tabs, a finding's section — follows from
 * a template having sections, so this one check is the whole boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplateSectionsGateTest {

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private AssessmentTypeRepository assessmentTypeRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;
    @Spy private EditionPolicy editionPolicy = new UnrestrictedEditionPolicy();

    @InjectMocks private ReportTemplateService service;

    private ReportTemplate existing;

    @BeforeEach
    void setUp() {
        when(assessmentTypeRepository.findById("type-1")).thenReturn(Optional.of(new AssessmentType()));
        when(reportTemplateRepository.existsByName(any())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class))).thenAnswer(i -> i.getArgument(0));
        existing = ReportTemplate.builder()
                .id("tmpl-1").name("Pentest").assessmentTypeId("type-1")
                .sections(new ArrayList<>(List.of("Web App")))
                .userDefinedFields(new ArrayList<>())
                .build();
        when(reportTemplateRepository.findById("tmpl-1")).thenReturn(Optional.of(existing));
    }

    private void communityEdition() {
        doReturn(false).when(editionPolicy).enabled(Feature.REPORT_SECTIONS);
    }

    private CreateReportTemplateRequest create(List<String> sections) {
        return CreateReportTemplateRequest.builder()
                .name("Pentest").assessmentTypeId("type-1").sections(sections).build();
    }

    @Test
    void enterpriseCreatesATemplateWithSections() {
        assertThatCode(() -> service.createReportTemplate(create(List.of("Web App")), "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    void communityCannotCreateATemplateWithSections() {
        communityEdition();

        assertThatThrownBy(() -> service.createReportTemplate(create(List.of("Web App")), "admin"))
                .isInstanceOf(FeatureNotLicensedException.class);
    }

    @Test
    void communityCreatesATemplateWithoutSectionsAsBefore() {
        communityEdition();

        assertThatCode(() -> service.createReportTemplate(create(List.of()), "admin"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.createReportTemplate(create(null), "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    void communityCannotAddSectionsOnUpdate() {
        communityEdition();
        UpdateReportTemplateRequest request = UpdateReportTemplateRequest.builder()
                .sections(List.of("Web App", "Mobile")).build();

        assertThatThrownBy(() -> service.updateReportTemplate("tmpl-1", request, "admin"))
                .isInstanceOf(FeatureNotLicensedException.class);
    }

    /** A database that once ran the overlay can still be tidied: clearing is never gated. */
    @Test
    void communityCanClearSectionsLeftByTheOverlay() {
        communityEdition();
        UpdateReportTemplateRequest request = UpdateReportTemplateRequest.builder()
                .sections(List.of()).build();

        assertThatCode(() -> service.updateReportTemplate("tmpl-1", request, "admin"))
                .doesNotThrowAnyException();
    }
}
