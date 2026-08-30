package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateReportTemplateRequest;
import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the report font plumbs through template create/update
 * (the Report Designer's "Report Font" input → DocxUtils.FONT).
 */
@ExtendWith(MockitoExtension.class)
class ReportTemplateServiceFontTest {

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private AssessmentTypeRepository assessmentTypeRepository;
    @Mock private com.faction.clientportal.repository.AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;

    @InjectMocks
    private ReportTemplateService service;

    @Test
    void createReportTemplate_persistsFont() {
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.of(AssessmentType.builder().id("type-1").name("Web").build()));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("Font Template");
        request.setAssessmentTypeId("type-1");
        request.setFont("Georgia");
        request.setUserDefinedFields(new ArrayList<>());

        ReportTemplateDto dto = service.createReportTemplate(request, "user-1");

        assertThat(dto.getFont()).isEqualTo("Georgia");
    }

    @Test
    void createReportTemplate_appliesDefaultCssWhenNoneProvided() {
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.of(AssessmentType.builder().id("type-1").name("Web").build()));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("No CSS Template");
        request.setAssessmentTypeId("type-1");
        request.setUserDefinedFields(new ArrayList<>());

        ReportTemplateDto dto = service.createReportTemplate(request, "user-1");

        assertThat(dto.getCss()).isEqualTo(ReportTemplateService.DEFAULT_TEMPLATE_CSS);
        assertThat(dto.getCss()).contains("font-family: Arial").contains("max-width: 600px");
    }

    @Test
    void createReportTemplate_addsDefaultSummaryFieldsWhenNoneProvided() {
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.of(AssessmentType.builder().id("type-1").name("Web").build()));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("No Fields Template");
        request.setAssessmentTypeId("type-1");
        request.setUserDefinedFields(new ArrayList<>());

        ReportTemplateDto dto = service.createReportTemplate(request, "user-1");

        assertThat(dto.getUserDefinedFields()).hasSize(2);
        assertThat(dto.getUserDefinedFields().get(0).getVariableName()).isEqualTo("summary1");
        assertThat(dto.getUserDefinedFields().get(0).getDisplayName()).isEqualTo("Executive Summary");
        assertThat(dto.getUserDefinedFields().get(0).getFieldType())
                .isEqualTo(com.faction.clientportal.model.FieldType.RICH_TEXT);
        assertThat(dto.getUserDefinedFields().get(1).getVariableName()).isEqualTo("summary2");
        assertThat(dto.getUserDefinedFields().get(1).getDisplayName()).isEqualTo("Scope");
        assertThat(dto.getUserDefinedFields().get(1).getFieldType())
                .isEqualTo(com.faction.clientportal.model.FieldType.RICH_TEXT);
    }

    @Test
    void createReportTemplate_keepsExplicitCss() {
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.of(AssessmentType.builder().id("type-1").name("Web").build()));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("Custom CSS Template");
        request.setAssessmentTypeId("type-1");
        request.setCss("h1 { color: teal; }");
        request.setUserDefinedFields(new ArrayList<>());

        ReportTemplateDto dto = service.createReportTemplate(request, "user-1");

        assertThat(dto.getCss()).isEqualTo("h1 { color: teal; }");
    }

    @Test
    void updateReportTemplate_updatesFontAndKeepsItWhenAbsent() {
        ReportTemplate existing = ReportTemplate.builder()
                .id("tmpl-1")
                .name("Existing")
                .assessmentTypeId("type-1")
                .font("Arial")
                .version(1)
                .active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        when(reportTemplateRepository.findById("tmpl-1")).thenReturn(Optional.of(existing));
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateReportTemplateRequest fontUpdate = new UpdateReportTemplateRequest();
        fontUpdate.setFont("Times New Roman");
        ReportTemplateDto updated = service.updateReportTemplate("tmpl-1", fontUpdate, "user-1");
        assertThat(updated.getFont()).isEqualTo("Times New Roman");

        // An unrelated update (font == null) must not clear the stored font
        UpdateReportTemplateRequest cssUpdate = new UpdateReportTemplateRequest();
        cssUpdate.setCss("h1 { color: blue; }");
        ReportTemplateDto afterCssUpdate = service.updateReportTemplate("tmpl-1", cssUpdate, "user-1");
        assertThat(afterCssUpdate.getFont()).isEqualTo("Times New Roman");
    }
}
