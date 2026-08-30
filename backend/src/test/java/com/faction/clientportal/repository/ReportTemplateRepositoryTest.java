package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReportTemplateRepositoryTest extends TestContainersConfig {

    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private AssessmentRepository     assessmentRepository;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
    }

    // Regression test: template CSS is edited in the Report Designer's CSS box
    // and routinely exceeds 255 characters. The css / template_css fields had no
    // @Column annotation, so Hibernate defaulted them to varchar(255) and any
    // save past that limit threw "value too long for type character varying(255)".
    // See V9__widen_css_columns.sql for the column-type fix on already-deployed
    // databases (ddl-auto=update does not alter existing columns).
    @Test
    void save_withCssOver255Chars_persistsWithoutTruncationError() {
        String longCss = ".rte-table { border: 1px solid #000; }\n".repeat(50); // ~2000 chars

        ReportTemplate template = reportTemplateRepository.save(ReportTemplate.builder()
                .name("Long CSS Template")
                .assessmentTypeId("type-1")
                .css(longCss)
                .version(1)
                .active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build());

        assertThat(reportTemplateRepository.findById(template.getId()))
                .hasValueSatisfying(t -> assertThat(t.getCss()).isEqualTo(longCss));

        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .name("Long CSS Assessment")
                .applicationId("app-1")
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .reportTemplateId(template.getId())
                .templateCss(longCss)
                .status("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .build());

        assertThat(assessmentRepository.findById(assessment.getId()))
                .hasValueSatisfying(a -> assertThat(a.getTemplateCss()).isEqualTo(longCss));
    }
}
