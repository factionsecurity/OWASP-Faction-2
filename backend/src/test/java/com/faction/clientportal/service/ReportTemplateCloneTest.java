package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReportTemplateService#cloneReportTemplate}: an exact duplicate of a template under a new
 * name — every field and variable, the CSS, sections and the uploaded DOCX (copied to the clone's
 * own storage key), with only the name, id and audit fields differing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplateCloneTest {

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private com.faction.clientportal.repository.AssessmentTypeRepository assessmentTypeRepository;
    @Mock private com.faction.clientportal.repository.AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;

    @InjectMocks private ReportTemplateService service;

    private ReportTemplate source;

    @BeforeEach
    void setUp() {
        source = ReportTemplate.builder()
                .id("src-1")
                .name("Web App Pentest")
                .description("Standard web report")
                .assessmentTypeId("type-1")
                .css("body { color: red; }")
                .font("Georgia")
                .scoringType("CVSS_31")
                .sections(new ArrayList<>(List.of("Findings", "Appendix")))
                .version(7)
                .active(true)
                .templateFileId("report-templates/src-1/report.docx")
                .templateFileName("report.docx")
                .templateFileSize(2048L)
                .templateFileContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .userDefinedFields(new ArrayList<>(List.of(
                        UserDefinedField.builder()
                                .id("f-1").variableName("executive_summary").displayName("Executive Summary")
                                .fieldType(FieldType.RICH_TEXT).fieldScope(FieldScope.ASSESSMENT)
                                .required(true).displayOrder(0).build(),
                        UserDefinedField.builder()
                                .id("f-2").variableName("business_impact").displayName("Business Impact")
                                .fieldType(FieldType.DROPDOWN).fieldScope(FieldScope.VULNERABILITY)
                                .dropdownOptions(new ArrayList<>(List.of("Low", "High")))
                                .displayOrder(1).build())))
                .createdBy("someone-else")
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();

        when(reportTemplateRepository.findById("src-1")).thenReturn(Optional.of(source));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class))).thenAnswer(inv -> {
            ReportTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId("clone-1");
            return t;
        });
    }

    @Test
    void copiesEverythingThatDefinesTheTemplate() {
        ReportTemplateDto dto = service.cloneReportTemplate("src-1", "Web App Pentest (Copy)", "user-1");

        assertThat(dto.getName()).isEqualTo("Web App Pentest (Copy)");
        assertThat(dto.getDescription()).isEqualTo("Standard web report");
        assertThat(dto.getAssessmentTypeId()).isEqualTo("type-1");
        assertThat(dto.getCss()).isEqualTo("body { color: red; }");
        assertThat(dto.getFont()).isEqualTo("Georgia");
        assertThat(dto.getScoringType()).isEqualTo("CVSS_31");
        assertThat(dto.getSections()).containsExactly("Findings", "Appendix");
        assertThat(dto.getActive()).isTrue();
    }

    @Test
    void copiesEveryFieldWithItsVariableNameSoDocxReferencesStillResolve() {
        ReportTemplateDto dto = service.cloneReportTemplate("src-1", "Copy", "user-1");

        assertThat(dto.getUserDefinedFields()).hasSize(2);
        assertThat(dto.getUserDefinedFields()).extracting("variableName")
                .containsExactly("executive_summary", "business_impact");
        assertThat(dto.getUserDefinedFields()).extracting("displayName")
                .containsExactly("Executive Summary", "Business Impact");
        assertThat(dto.getUserDefinedFields()).extracting("fieldScope")
                .containsExactly(FieldScope.ASSESSMENT, FieldScope.VULNERABILITY);
        assertThat(dto.getUserDefinedFields().get(1).getDropdownOptions()).containsExactly("Low", "High");
    }

    @Test
    void fieldsAreDetachedCopies_editingTheCloneCannotMutateTheSource() {
        service.cloneReportTemplate("src-1", "Copy", "user-1");

        ReportTemplate clone = savedClone();
        assertThat(clone.getUserDefinedFields()).isNotSameAs(source.getUserDefinedFields());
        for (int i = 0; i < clone.getUserDefinedFields().size(); i++) {
            assertThat(clone.getUserDefinedFields().get(i)).isNotSameAs(source.getUserDefinedFields().get(i));
        }
        assertThat(clone.getSections()).isNotSameAs(source.getSections());

        // Mutating the clone's nested list must not reach the source's.
        clone.getUserDefinedFields().get(1).getDropdownOptions().add("Critical");
        assertThat(source.getUserDefinedFields().get(1).getDropdownOptions()).containsExactly("Low", "High");
    }

    @Test
    void startsAtVersionOneAndIsOwnedByTheCloningUser() {
        service.cloneReportTemplate("src-1", "Copy", "user-1");

        ReportTemplate clone = savedClone();
        // The clone is a new template, not a revision of the source — its version tracks its own edits.
        assertThat(clone.getVersion()).isEqualTo(1);
        assertThat(clone.getCreatedBy()).isEqualTo("user-1");
        assertThat(clone.getLastUpdatedBy()).isEqualTo("user-1");
    }

    @Test
    void copiesTheDocxToItsOwnKeySoTheTwoTemplatesNeverShareAFile() {
        byte[] docx = "PK-docx-bytes".getBytes();
        when(storageService.downloadBytes("report-templates/src-1/report.docx")).thenReturn(docx);

        ReportTemplateDto dto = service.cloneReportTemplate("src-1", "Copy", "user-1");

        verify(storageService).uploadBytes(
                eq("report-templates/clone-1/report.docx"), eq(docx),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertThat(dto.getTemplateFileId()).isEqualTo("report-templates/clone-1/report.docx");
        assertThat(dto.getTemplateFileName()).isEqualTo("report.docx");
        assertThat(dto.getTemplateFileSize()).isEqualTo(2048L);
        // The source's object is untouched.
        assertThat(source.getTemplateFileId()).isEqualTo("report-templates/src-1/report.docx");
    }

    @Test
    void aStorageFailureStillYieldsAClone_justWithoutTheFile() {
        when(storageService.downloadBytes(anyString())).thenThrow(new RuntimeException("MinIO down"));

        ReportTemplateDto dto = service.cloneReportTemplate("src-1", "Copy", "user-1");

        // Losing the DOCX is recoverable (upload one); losing the whole copy is not.
        assertThat(dto.getName()).isEqualTo("Copy");
        assertThat(dto.getUserDefinedFields()).hasSize(2);
        assertThat(dto.getTemplateFileId()).isNull();
    }

    @Test
    void aSourceWithNoFileClonesWithoutTouchingStorage() {
        source.setTemplateFileId(null);

        service.cloneReportTemplate("src-1", "Copy", "user-1");

        verify(storageService, never()).downloadBytes(anyString());
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
    }

    @Test
    void rejectsADuplicateName() {
        when(reportTemplateRepository.existsByName("Taken")).thenReturn(true);

        assertThatThrownBy(() -> service.cloneReportTemplate("src-1", "Taken", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> service.cloneReportTemplate("src-1", "   ", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void trimsTheName() {
        service.cloneReportTemplate("src-1", "  Padded Copy  ", "user-1");

        assertThat(savedClone().getName()).isEqualTo("Padded Copy");
    }

    @Test
    void unknownSourceIsNotFound() {
        when(reportTemplateRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cloneReportTemplate("nope", "Copy", "user-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** The first template handed to save() that isn't the source — i.e. the new clone. */
    private ReportTemplate savedClone() {
        var captor = org.mockito.ArgumentCaptor.forClass(ReportTemplate.class);
        verify(reportTemplateRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(t -> !"src-1".equals(t.getId()))
                .findFirst()
                .orElseThrow();
    }
}
