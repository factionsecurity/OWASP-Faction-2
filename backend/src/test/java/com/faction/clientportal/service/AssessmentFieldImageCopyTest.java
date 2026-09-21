package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.UpdateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assessment variables carried forward from a previous assessment arrive holding that assessment's
 * screenshots. An inline image authorises against the assessment that owns it, so each one has to
 * be copied in — otherwise it renders broken for anyone who can read the new assessment but not the
 * old one, and disappears with it.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentFieldImageCopyTest extends TestContainersConfig {

    private static final String FIELD_ID = "exec-summary";
    private static final Pattern IMAGE_REF = Pattern.compile("/api/v1/inline-images/([a-zA-Z0-9]+)");

    @Autowired private AssessmentService assessmentService;
    @Autowired private InlineImageService inlineImageService;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private InlineImageRefRepository inlineImageRefRepository;

    private Application application;
    private Assessment previous;
    private AssessmentType type;
    private ReportTemplate template;

    @BeforeEach
    void setUp() {
        inlineImageRefRepository.deleteAll();
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();

        application = applicationRepository.save(Application.builder()
                .name("Payments").description("d").organizationId("org-1")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        previous = assessmentRepository.save(Assessment.builder()
                .name("Last year").applicationId(application.getId()).organizationId("org-1")
                .assessmentTypeId("t").status("Completed")
                .createdAt(LocalDateTime.now()).build());

        type = assessmentTypeRepository.save(AssessmentType.builder()
                // Type names are unique and this class does not clear the table.
                .name("Pen Test " + java.util.UUID.randomUUID()).description("d")
                .createdAt(LocalDateTime.now()).build());
        UserDefinedField field = UserDefinedField.builder()
                .id(FIELD_ID).displayName("Executive Summary").variableName("execSummary")
                .fieldType(FieldType.RICH_TEXT).fieldScope(FieldScope.ASSESSMENT).displayOrder(0).build();
        template = reportTemplateRepository.save(ReportTemplate.builder()
                .name("T " + java.util.UUID.randomUUID()).description("d")
                .assessmentTypeId(type.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>(List.of(field)))
                .createdAt(LocalDateTime.now()).build());
    }

    private String uploadImage(String assessmentId) {
        return inlineImageService.uploadImage(
                assessmentId, "shot.png", "image/png", new byte[]{1, 2, 3}, "tester").getId();
    }

    private String tag(String imageId) {
        return "<p>Summary</p><img src=\"/api/v1/inline-images/" + imageId + "\">";
    }

    private List<String> imageIdsIn(String html) {
        List<String> ids = new ArrayList<>();
        Matcher m = IMAGE_REF.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private AssessmentDto create(Map<String, String> fieldValues) {
        return assessmentService.createAssessment(CreateAssessmentRequest.builder()
                .name("This year")
                .applicationId(application.getId())
                .assessmentTypeId(type.getId())
                .reportTemplateId(template.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .initialFieldValues(fieldValues)
                .build(), "tester");
    }

    private String storedValue(String assessmentId) {
        return assessmentRepository.findById(assessmentId).orElseThrow().getFieldValues().get(FIELD_ID);
    }

    @Test
    void creatingWithAnotherAssessmentsImageCopiesItIn() {
        String original = uploadImage(previous.getId());

        String createdId = create(Map.of(FIELD_ID, tag(original))).getId();

        List<String> ids = imageIdsIn(storedValue(createdId));
        assertThat(ids).hasSize(1);
        String copy = ids.get(0);
        assertThat(copy).isNotEqualTo(original);
        assertThat(inlineImageService.getAssessmentId(copy)).isEqualTo(createdId);
        // Indexed, or the GC reaps the copy the next night.
        assertThat(inlineImageService.hasRefs(copy)).isTrue();
    }

    @Test
    void theSameImageTwiceInOneValueIsCopiedOnce() {
        String original = uploadImage(previous.getId());

        String createdId = create(Map.of(FIELD_ID, tag(original) + tag(original))).getId();

        List<String> ids = imageIdsIn(storedValue(createdId));
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isEqualTo(ids.get(1)).isNotEqualTo(original);
    }

    @Test
    void editingInAnotherAssessmentsImageCopiesItIn() {
        String createdId = create(Map.of()).getId();
        String original = uploadImage(previous.getId());

        assessmentService.updateAssessment(createdId, UpdateAssessmentRequest.builder()
                .fieldValues(Map.of(FIELD_ID, tag(original))).build(), "tester");

        String copy = imageIdsIn(storedValue(createdId)).get(0);
        assertThat(copy).isNotEqualTo(original);
        assertThat(inlineImageService.getAssessmentId(copy)).isEqualTo(createdId);
        assertThat(inlineImageService.hasRefs(copy)).isTrue();
    }

    @Test
    void anImageTheAssessmentAlreadyOwnsIsLeftAlone() {
        String createdId = create(Map.of()).getId();
        String own = uploadImage(createdId);

        assessmentService.updateAssessment(createdId, UpdateAssessmentRequest.builder()
                .fieldValues(Map.of(FIELD_ID, tag(own))).build(), "tester");

        // Re-saving a field must not copy its images every time.
        assertThat(imageIdsIn(storedValue(createdId))).containsExactly(own);
        assertThat(inlineImageRepository.count()).isEqualTo(1);
    }
}
