package com.faction.clientportal.service;

import com.faction.clientportal.dto.CreateReportTemplateRequest;
import com.faction.clientportal.dto.UpdateReportTemplateRequest;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.ReportPalette;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The colour palette as a property of the report template.
 *
 * <p>A new template starts with the web UI's severity colours rather than nothing, so a report and
 * the screen it came from do not disagree about what Critical looks like, and an author who never
 * opens the colour pickers still gets a sensible report.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTemplatePaletteTest {

    @Mock private ReportTemplateRepository reportTemplateRepository;
    @Mock private com.faction.clientportal.repository.AssessmentTypeRepository assessmentTypeRepository;
    @Mock private com.faction.clientportal.repository.AssessmentRepository assessmentRepository;
    @Mock private StorageService storageService;

    @InjectMocks private ReportTemplateService service;

    @BeforeEach
    void setUp() {
        when(assessmentTypeRepository.findById("type-1"))
                .thenReturn(Optional.of(AssessmentType.builder().id("type-1").name("Pentest").build()));
        when(reportTemplateRepository.existsByName(any())).thenReturn(false);
        when(reportTemplateRepository.save(any(ReportTemplate.class)))
                .thenAnswer(invocation -> {
                    ReportTemplate t = invocation.getArgument(0);
                    if (t.getId() == null) t.setId("new-1");
                    return t;
                });
    }

    private ReportTemplate captureSaved() {
        ArgumentCaptor<ReportTemplate> captor = ArgumentCaptor.forClass(ReportTemplate.class);
        verify(reportTemplateRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aNewTemplateIsSeededWithTheDefaultSeverityColours() {
        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("Web App Pentest");
        request.setAssessmentTypeId("type-1");

        service.createReportTemplate(request, "user-1");

        ReportPalette palette = captureSaved().getReportPalette();
        assertThat(palette).isNotNull();
        assertThat(palette.getSeverity().get("CRITICAL").getText()).isEqualTo("B91C1C");
        assertThat(palette.getSeverity().get("CRITICAL").getFill()).isEqualTo("FCE1E1");
    }

    /**
     * Likelihood and impact are the same five levels as severity, so a new template colours all
     * three the same way and the designer shows them as one setting. Custom fields have no values
     * to colour until someone defines a dropdown field, so they start empty.
     */
    @Test
    void aNewTemplateColoursAllThreeRatingsTheSameAndSplitsNone() {
        CreateReportTemplateRequest request = new CreateReportTemplateRequest();
        request.setName("Web App Pentest");
        request.setAssessmentTypeId("type-1");

        service.createReportTemplate(request, "user-1");

        ReportPalette palette = captureSaved().getReportPalette();
        assertThat(palette.getLikelihood()).isEqualTo(palette.getSeverity());
        assertThat(palette.getImpact()).isEqualTo(palette.getSeverity());
        assertThat(palette.getSeparateRatingColours()).isFalse();
        assertThat(palette.getCustomFields()).isEmpty();
    }

    // ── cloning ──────────────────────────────────────────────────────────────

    /**
     * The clone must not share the source's palette object. Both are jsonb-mapped collections on
     * managed entities, so a shared map means recolouring one template silently recolours the
     * other's reports — the same trap {@code UserDefinedField.copy()} exists to avoid.
     */
    @Test
    void cloningATemplateGivesItAPaletteOfItsOwn() {
        ReportPalette original = ReportPalette.defaults();
        original.allocateSlot("risk_rating");

        ReportTemplate source = ReportTemplate.builder()
                .id("src-1").name("Web App Pentest").assessmentTypeId("type-1")
                .reportPalette(original)
                .sections(new ArrayList<>())
                .userDefinedFields(new ArrayList<>())
                .version(1).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(reportTemplateRepository.findById("src-1")).thenReturn(Optional.of(source));

        service.cloneReportTemplate("src-1", "Copy of Web App Pentest", "user-1");

        ArgumentCaptor<ReportTemplate> captor = ArgumentCaptor.forClass(ReportTemplate.class);
        verify(reportTemplateRepository).save(captor.capture());
        ReportPalette clonePalette = captor.getValue().getReportPalette();

        assertThat(clonePalette).isNotSameAs(original);
        assertThat(clonePalette.getSeverity()).isNotSameAs(original.getSeverity());
        assertThat(clonePalette.getCustomFields().get("risk_rating").getSlot()).isEqualTo(4);

        clonePalette.getSeverity().put("CRITICAL", ReportPalette.ColourPair.of("000000", "FFFFFF"));
        assertThat(original.getSeverity().get("CRITICAL").getText()).isEqualTo("B91C1C");
    }

    /**
     * A template created before this feature has a null palette. Cloning it must not fall over,
     * and the clone should start from the defaults rather than inheriting the null.
     */
    @Test
    void cloningATemplateThatPredatesPalettesSeedsTheDefaults() {
        ReportTemplate source = ReportTemplate.builder()
                .id("src-1").name("Legacy").assessmentTypeId("type-1")
                .reportPalette(null)
                .sections(new ArrayList<>())
                .userDefinedFields(new ArrayList<>())
                .version(1).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(reportTemplateRepository.findById("src-1")).thenReturn(Optional.of(source));

        service.cloneReportTemplate("src-1", "Copy of Legacy", "user-1");

        ReportPalette clonePalette = captureSaved().getReportPalette();
        assertThat(clonePalette).isNotNull();
        assertThat(clonePalette.getSeverity()).containsKey("CRITICAL");
    }

    // ── saving from the designer ─────────────────────────────────────────────

    private ReportTemplate existing(ReportPalette palette) {
        ReportTemplate template = ReportTemplate.builder()
                .id("t1").name("Web App Pentest").assessmentTypeId("type-1")
                .reportPalette(palette)
                .sections(new ArrayList<>())
                .userDefinedFields(new ArrayList<>())
                .version(1).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(reportTemplateRepository.findById("t1")).thenReturn(Optional.of(template));
        return template;
    }

    @Test
    void aPaletteEditedInTheDesignerIsSaved() {
        existing(ReportPalette.defaults());

        ReportPalette edited = ReportPalette.defaults();
        edited.getSeverity().put("CRITICAL", ReportPalette.ColourPair.of("990000", "FFCCCC"));
        edited.getLikelihood().put("High", ReportPalette.ColourPair.of("AA0000", "FFDDDD"));

        UpdateReportTemplateRequest request = new UpdateReportTemplateRequest();
        request.setReportPalette(edited);

        service.updateReportTemplate("t1", request, "user-1");

        ReportPalette saved = captureSaved().getReportPalette();
        assertThat(saved.getSeverity().get("CRITICAL").getText()).isEqualTo("990000");
        assertThat(saved.getLikelihood().get("High").getFill()).isEqualTo("FFDDDD");
    }

    /**
     * An update that says nothing about colours leaves them alone — the designer saves the whole
     * template on every edit, and a CSS change must not wipe the palette.
     */
    @Test
    void anUpdateWithNoPaletteLeavesTheExistingOneAlone() {
        ReportPalette original = ReportPalette.defaults();
        original.getSeverity().put("CRITICAL", ReportPalette.ColourPair.of("990000", "FFCCCC"));
        existing(original);

        UpdateReportTemplateRequest request = new UpdateReportTemplateRequest();
        request.setCss("body { color: red; }");

        service.updateReportTemplate("t1", request, "user-1");

        assertThat(captureSaved().getReportPalette().getSeverity().get("CRITICAL").getText())
                .isEqualTo("990000");
    }

    /** A template created before palettes existed gets one the first time it is saved. */
    @Test
    void savingATemplateThatPredatesPalettesSeedsTheDefaults() {
        existing(null);

        UpdateReportTemplateRequest request = new UpdateReportTemplateRequest();
        request.setCss("body {}");

        service.updateReportTemplate("t1", request, "user-1");

        ReportPalette saved = captureSaved().getReportPalette();
        assertThat(saved).isNotNull();
        assertThat(saved.getSeverity()).containsKey("CRITICAL");
    }
}
