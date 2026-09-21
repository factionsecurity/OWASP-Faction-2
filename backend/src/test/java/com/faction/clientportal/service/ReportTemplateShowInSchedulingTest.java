package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.dto.UserDefinedFieldDto;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The Report Designer's "Show in Scheduling" checkbox: which assessment variables the scheduling
 * form offers. Assessments only re-read a template's fields when its version advances, so ticking
 * the box has to count as a field change — or existing assessments never learn about it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplateShowInSchedulingTest {

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private AssessmentTypeRepository assessmentTypeRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;

    @InjectMocks private ReportTemplateService service;

    private ReportTemplate template;

    @BeforeEach
    void setUp() {
        template = ReportTemplate.builder()
                .id("t-1").name("Web").assessmentTypeId("type-1").version(3).active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        when(reportTemplateRepository.findById("t-1")).thenReturn(Optional.of(template));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class))).thenAnswer(inv -> {
            ReportTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId("clone-1");
            return t;
        });
    }

    private UserDefinedField field(Boolean showInScheduling) {
        return UserDefinedField.builder()
                .id("f-1").variableName("product-owner").displayName("Product Owner")
                .fieldType(FieldType.STRING).fieldScope(FieldScope.ASSESSMENT)
                .required(false).displayOrder(0)
                .showInScheduling(showInScheduling)
                .build();
    }

    /** The field exactly as the designer sends it back, apart from the checkbox. */
    private UpdateReportTemplateRequest resave(Boolean showInScheduling) {
        UserDefinedFieldDto dto = UserDefinedFieldDto.builder()
                .id("f-1").variableName("product-owner").displayName("Product Owner")
                .fieldType(FieldType.STRING).fieldScope(FieldScope.ASSESSMENT)
                .required(false).displayOrder(0)
                .showInScheduling(showInScheduling)
                .build();
        UpdateReportTemplateRequest request = new UpdateReportTemplateRequest();
        request.setUserDefinedFields(new ArrayList<>(List.of(dto)));
        return request;
    }

    @Test
    void tickingShowInSchedulingIsSavedAndAdvancesTheVersion() {
        template.getUserDefinedFields().add(field(false));

        ReportTemplateDto result = service.updateReportTemplate("t-1", resave(true), "user-1");

        assertThat(result.getUserDefinedFields().get(0).getShowInScheduling()).isTrue();
        assertThat(result.getVersion()).isEqualTo(4);
    }

    @Test
    void resavingAFieldFromBeforeTheCheckboxExistedKeepsTheVersion() {
        // Stored before the flag existed, so the JSON has no key and it reads back as null. The
        // designer sends it back unticked; that is the same field, not a structural change.
        template.getUserDefinedFields().add(field(null));

        ReportTemplateDto result = service.updateReportTemplate("t-1", resave(false), "user-1");

        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    void aNewFieldIsNotShownInSchedulingUnlessTicked() {
        UpdateReportTemplateRequest request = resave(null);

        ReportTemplateDto result = service.updateReportTemplate("t-1", request, "user-1");

        assertThat(result.getUserDefinedFields().get(0).getShowInScheduling()).isFalse();
    }

    @Test
    void aClonedTemplateKeepsTheCheckbox() {
        template.getUserDefinedFields().add(field(true));

        ReportTemplateDto clone = service.cloneReportTemplate("t-1", "Web copy", "user-1");

        assertThat(clone.getUserDefinedFields().get(0).getShowInScheduling()).isTrue();
    }
}
