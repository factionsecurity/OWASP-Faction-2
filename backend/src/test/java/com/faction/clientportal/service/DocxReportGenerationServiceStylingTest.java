package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportPalette;
import com.faction.clientportal.model.ReportTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The styling an assessment reports with follows the live template.
 *
 * <p>An assessment snapshots its template's CSS, font and DOCX file, and that snapshot used to
 * be refreshed only when the assessment was loaded through AssessmentService. Editing the CSS
 * in the report designer and generating straight afterwards produced a report in the old CSS,
 * while the same edit took effect once anything reloaded the assessment — so the designer
 * looked like it had not saved.
 */
class DocxReportGenerationServiceStylingTest {

    private static Assessment assessmentWith(String css, String font, String fileId) {
        return Assessment.builder()
                .id("a1").reportTemplateId("t1")
                .templateCss(css).templateFont(font).templateFileId(fileId)
                .build();
    }

    @Test
    void picksUpCssEditedInTheDesigner() {
        Assessment assessment = assessmentWith("h1 { color: red; }", "Arial", "file-1");
        ReportTemplate template = ReportTemplate.builder()
                .id("t1").css("h1 { color: blue; }").font("Arial").templateFileId("file-1").build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplateCss()).isEqualTo("h1 { color: blue; }");
    }

    @Test
    void picksUpFontAndDocxChanges() {
        Assessment assessment = assessmentWith("body {}", "Arial", "file-1");
        ReportTemplate template = ReportTemplate.builder()
                .id("t1").css("body {}").font("Calibri").templateFileId("file-2").build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplateFont()).isEqualTo("Calibri");
        assertThat(assessment.getTemplateFileId()).isEqualTo("file-2");
    }

    /** The old behaviour this replaces: a null file id on the snapshot is still backfilled. */
    @Test
    void backfillsAMissingDocxFile() {
        Assessment assessment = assessmentWith("body {}", "Arial", null);
        ReportTemplate template = ReportTemplate.builder()
                .id("t1").templateFileId("file-1").build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplateFileId()).isEqualTo("file-1");
    }

    /** "Not set" on the template must not wipe styling the assessment already has. */
    @Test
    void leavesTheSnapshotAloneWhereTheTemplateHasNothing() {
        Assessment assessment = assessmentWith("h1 { color: red; }", "Arial", "file-1");
        ReportTemplate template = ReportTemplate.builder().id("t1").build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplateCss()).isEqualTo("h1 { color: red; }");
        assertThat(assessment.getTemplateFont()).isEqualTo("Arial");
        assertThat(assessment.getTemplateFileId()).isEqualTo("file-1");
    }

    /** An assessment with no template at all keeps whatever it was given. */
    @Test
    void toleratesAnAssessmentWithNoTemplate() {
        Assessment assessment = assessmentWith("h1 { color: red; }", "Arial", "file-1");

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, null);

        assertThat(assessment.getTemplateCss()).isEqualTo("h1 { color: red; }");
    }

    // ── colour palette ───────────────────────────────────────────────────────

    /**
     * The palette follows the live template exactly as CSS does, so recolouring severities in the
     * designer takes effect on the next generation rather than the one after.
     */
    @Test
    void picksUpPaletteEditsFromTheDesigner() {
        Assessment assessment = assessmentWith("body {}", "Arial", "file-1");
        assessment.setTemplatePalette(ReportPalette.defaults());

        ReportPalette edited = ReportPalette.defaults();
        edited.putSeverity("CRITICAL", ReportPalette.ColourPair.of("990000", "FFCCCC"));
        ReportTemplate template = ReportTemplate.builder()
                .id("t1").css("body {}").font("Arial").templateFileId("file-1")
                .reportPalette(edited).build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplatePalette().getSeverity().get("CRITICAL").getText())
                .isEqualTo("990000");
    }

    /**
     * Why the palette is snapshotted at all rather than read from the template each time: a
     * template can be deleted while assessments still reference it, and this method returns early
     * on a null. The snapshot is what keeps those reports rendering in colour instead of black.
     */
    @Test
    void keepsTheSnapshottedPaletteWhenTheTemplateIsGone() {
        Assessment assessment = assessmentWith("body {}", "Arial", "file-1");
        assessment.setTemplatePalette(ReportPalette.defaults());

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, null);

        assertThat(assessment.getTemplatePalette()).isNotNull();
        assertThat(assessment.getTemplatePalette().getSeverity()).containsKey("CRITICAL");
    }

    /** A null on the template means "not set" and leaves the snapshot alone, as with CSS. */
    @Test
    void aTemplateWithNoPaletteDoesNotClearTheSnapshot() {
        Assessment assessment = assessmentWith("body {}", "Arial", "file-1");
        assessment.setTemplatePalette(ReportPalette.defaults());

        ReportTemplate template = ReportTemplate.builder()
                .id("t1").css("body {}").reportPalette(null).build();

        DocxReportGenerationService.applyLiveTemplateStyling(assessment, template);

        assertThat(assessment.getTemplatePalette()).isNotNull();
    }
}
