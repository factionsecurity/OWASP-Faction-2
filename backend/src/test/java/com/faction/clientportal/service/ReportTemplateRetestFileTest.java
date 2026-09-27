package com.faction.clientportal.service;

import com.faction.clientportal.dto.ReportTemplateDto;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.ReportTemplate;
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
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
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
 * The optional retest DOCX on a report template: a second document slot that sits beside the main
 * one, sharing everything else about the template, and never disturbing the main file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplateRetestFileTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private AssessmentTypeRepository assessmentTypeRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;

    @InjectMocks private ReportTemplateService service;

    private ReportTemplate template;

    @BeforeEach
    void setUp() {
        template = ReportTemplate.builder()
                .id("tpl-1")
                .name("Web App Pentest")
                .active(true)
                .sections(new ArrayList<>())
                .userDefinedFields(new ArrayList<>())
                .templateFileId("report-templates/tpl-1/report.docx")
                .templateFileName("report.docx")
                .templateFileSize(2048L)
                .templateFileContentType(DOCX)
                .build();

        when(reportTemplateRepository.findById("tpl-1")).thenReturn(Optional.of(template));
        when(reportTemplateRepository.existsByName(anyString())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class))).thenAnswer(inv -> {
            ReportTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId("clone-1");
            return t;
        });
    }

    @Test
    void uploadStoresTheRetestFileUnderItsOwnPrefixAndLeavesTheMainFileAlone() throws Exception {
        var file = new MockMultipartFile("file", "retest.docx", DOCX, "PK-retest".getBytes());

        ReportTemplateDto dto = service.uploadRetestTemplateFile("tpl-1", file, "user-1");

        verify(storageService).uploadBytes(eq("report-templates/tpl-1/retest/retest.docx"), any(), eq(DOCX));
        assertThat(dto.getRetestTemplateFileId()).isEqualTo("report-templates/tpl-1/retest/retest.docx");
        assertThat(dto.getRetestTemplateFileName()).isEqualTo("retest.docx");
        assertThat(dto.getRetestTemplateFileSize()).isEqualTo(9L);
        assertThat(dto.getRetestTemplateFileContentType()).isEqualTo(DOCX);
        assertThat(dto.getTemplateFileId()).isEqualTo("report-templates/tpl-1/report.docx");
        assertThat(dto.getTemplateFileName()).isEqualTo("report.docx");
        verify(storageService, never()).deleteObject(anyString());
    }

    @Test
    void aRetestFileNamedLikeTheMainFileDoesNotOverwriteIt() throws Exception {
        var file = new MockMultipartFile("file", "report.docx", DOCX, "PK-retest".getBytes());

        service.uploadRetestTemplateFile("tpl-1", file, "user-1");

        verify(storageService).uploadBytes(eq("report-templates/tpl-1/retest/report.docx"), any(), eq(DOCX));
        verify(storageService, never()).uploadBytes(eq("report-templates/tpl-1/report.docx"), any(), anyString());
    }

    @Test
    void replacingTheRetestFileDeletesOnlyTheOldRetestObject() throws Exception {
        template.setRetestTemplateFileId("report-templates/tpl-1/retest/old.docx");
        var file = new MockMultipartFile("file", "new.docx", DOCX, "PK-new".getBytes());

        service.uploadRetestTemplateFile("tpl-1", file, "user-1");

        verify(storageService).deleteObject("report-templates/tpl-1/retest/old.docx");
        verify(storageService, never()).deleteObject("report-templates/tpl-1/report.docx");
        assertThat(template.getRetestTemplateFileName()).isEqualTo("new.docx");
    }

    @Test
    void uploadRejectsANonDocx() {
        var file = new MockMultipartFile("file", "retest.pdf", "application/pdf", "%PDF".getBytes());

        assertThatThrownBy(() -> service.uploadRetestTemplateFile("tpl-1", file, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCX");
        verify(storageService, never()).uploadBytes(anyString(), any(), anyString());
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        var file = new MockMultipartFile("file", "retest.docx", DOCX, new byte[0]);

        assertThatThrownBy(() -> service.uploadRetestTemplateFile("tpl-1", file, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void downloadReturnsTheRetestBytes() {
        template.setRetestTemplateFileId("report-templates/tpl-1/retest/retest.docx");
        byte[] bytes = "PK-retest".getBytes();
        when(storageService.downloadBytes("report-templates/tpl-1/retest/retest.docx")).thenReturn(bytes);

        assertThat(service.downloadRetestTemplateFile("tpl-1")).isEqualTo(bytes);
    }

    @Test
    void downloadWithNoRetestFileIsNotFound() {
        assertThatThrownBy(() -> service.downloadRetestTemplateFile("tpl-1"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(storageService, never()).downloadBytes(anyString());
    }

    @Test
    void cloningCopiesTheRetestFileToTheClonesOwnKey() {
        template.setRetestTemplateFileId("report-templates/tpl-1/retest/retest.docx");
        template.setRetestTemplateFileName("retest.docx");
        template.setRetestTemplateFileSize(9L);
        template.setRetestTemplateFileContentType(DOCX);
        byte[] bytes = "PK-retest".getBytes();
        when(storageService.downloadBytes("report-templates/tpl-1/retest/retest.docx")).thenReturn(bytes);

        ReportTemplateDto dto = service.cloneReportTemplate("tpl-1", "Copy", "user-1");

        verify(storageService).uploadBytes(eq("report-templates/clone-1/retest/retest.docx"), eq(bytes), eq(DOCX));
        assertThat(dto.getRetestTemplateFileId()).isEqualTo("report-templates/clone-1/retest/retest.docx");
        assertThat(dto.getRetestTemplateFileName()).isEqualTo("retest.docx");
        assertThat(dto.getRetestTemplateFileSize()).isEqualTo(9L);
        assertThat(template.getRetestTemplateFileId()).isEqualTo("report-templates/tpl-1/retest/retest.docx");
    }

    @Test
    void hardDeletingTheTemplateRemovesTheRetestObjectToo() {
        template.setRetestTemplateFileId("report-templates/tpl-1/retest/retest.docx");
        when(assessmentRepository.existsByReportTemplateIdAndDeletedAtIsNull("tpl-1")).thenReturn(false);

        service.deleteReportTemplate("tpl-1");

        verify(storageService).deleteObject("report-templates/tpl-1/report.docx");
        verify(storageService).deleteObject("report-templates/tpl-1/retest/retest.docx");
    }
}
