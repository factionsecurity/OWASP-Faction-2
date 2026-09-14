package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.dto.UserDefinedFieldDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduling form's edit mode reads an assessment's own copy of the template fields, so a box
 * ticked in the Report Designer after the assessment was created has to reach that copy — along with
 * the values already entered, which are keyed by the copy's field ids.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentShowInSchedulingSyncTest extends TestContainersConfig {

    @Autowired private AssessmentService assessmentService;
    @Autowired private ReportTemplateService reportTemplateService;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;

    @Test
    void anExistingAssessmentPicksUpATickedBoxAndKeepsItsValues() {
        Application application = applicationRepository.save(Application.builder()
                .name("Payments " + UUID.randomUUID()).description("d").organizationId("org-1")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        AssessmentType type = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Pen Test " + UUID.randomUUID()).description("d")
                .createdAt(LocalDateTime.now()).build());
        UserDefinedField field = UserDefinedField.builder()
                .id("owner").variableName("product-owner").displayName("Product Owner")
                .fieldType(FieldType.STRING).fieldScope(FieldScope.ASSESSMENT).displayOrder(0)
                .showInScheduling(false).build();
        ReportTemplate template = reportTemplateRepository.save(ReportTemplate.builder()
                .name("T " + UUID.randomUUID()).description("d")
                .assessmentTypeId(type.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>(List.of(field)))
                .createdAt(LocalDateTime.now()).build());

        String assessmentId = assessmentService.createAssessment(CreateAssessmentRequest.builder()
                .name("Q3")
                .applicationId(application.getId())
                .assessmentTypeId(type.getId())
                .reportTemplateId(template.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .initialFieldValues(Map.of("owner", "Jane"))
                .build(), "tester").getId();

        UpdateReportTemplateRequest tick = new UpdateReportTemplateRequest();
        tick.setUserDefinedFields(new ArrayList<>(List.of(UserDefinedFieldDto.builder()
                .id("owner").variableName("product-owner").displayName("Product Owner")
                .fieldType(FieldType.STRING).fieldScope(FieldScope.ASSESSMENT).displayOrder(0)
                .required(false).showInScheduling(true).build())));
        reportTemplateService.updateReportTemplate(template.getId(), tick, "admin");

        var reloaded = assessmentService.getAssessment(assessmentId);

        assertThat(reloaded.getFieldDefinitions()).hasSize(1);
        assertThat(reloaded.getFieldDefinitions().get(0).getShowInScheduling()).isTrue();
        assertThat(reloaded.getFieldValues()).containsEntry("owner", "Jane");
    }
}
