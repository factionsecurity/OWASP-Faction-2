package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateNotebookNodeRequest;
import com.faction.clientportal.dto.UpdateNotebookNodeRequest;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.NotebookNodeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last two surfaces that were leaking screenshots to {@link
 * com.faction.clientportal.scheduled.InlineImageGcJob}: notebook notes, which were never indexed
 * at all, and assessment fields populated at creation, which were only indexed by a later update.
 * {@code hasRefs} is the exact predicate the job tests before deleting.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotebookAndAssessmentImageIndexTest extends TestContainersConfig {

    @Autowired private NotebookService notebookService;
    @Autowired private InlineImageService inlineImageService;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private NotebookNodeRepository notebookNodeRepository;
    @Autowired private AssessmentService assessmentService;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private InlineImageRefRepository inlineImageRefRepository;

    private Application application;
    private Assessment assessment;

    @BeforeEach
    void setUp() {
        notebookNodeRepository.deleteAll();
        // Images and their references are global tables — without clearing them, a reference left
        // behind by another class is indistinguishable from one this class created.
        inlineImageRefRepository.deleteAll();
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();

        application = applicationRepository.save(Application.builder()
                .name("Payments").description("d").organizationId("org-1")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        assessment = assessmentRepository.save(Assessment.builder()
                .name("Q1").applicationId(application.getId()).organizationId("org-1")
                .assessmentTypeId("t").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build());
    }

    private String uploadImage(String assessmentId) {
        return inlineImageService.uploadImage(
                assessmentId, "shot.png", "image/png", new byte[]{1, 2, 3}, "tester").getId();
    }

    private String tag(String imageId) {
        return "<p>Note</p><img src=\"/api/v1/inline-images/" + imageId + "\">";
    }

    @Test
    void aNoteHoldsItsScreenshotsOpen() {
        String imageId = uploadImage(assessment.getId());

        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle("Recon");
        request.setContent(tag(imageId));
        notebookService.createNode(application.getId(), request, "tester");

        assertThat(inlineImageService.hasRefs(imageId)).isTrue();
    }

    @Test
    void editingANoteReleasesTheImageItRemoved() {
        String original = uploadImage(assessment.getId());
        String replacement = uploadImage(assessment.getId());

        CreateNotebookNodeRequest create = new CreateNotebookNodeRequest();
        create.setTitle("Recon");
        create.setContent(tag(original));
        String nodeId = notebookService.createNode(application.getId(), create, "tester").getId();

        UpdateNotebookNodeRequest update = new UpdateNotebookNodeRequest();
        update.setContent(tag(replacement));
        notebookService.updateNode(nodeId, update, "tester");

        assertThat(inlineImageService.hasRefs(replacement)).isTrue();
        assertThat(inlineImageService.hasRefs(original)).isFalse();
    }

    @Test
    void aNoteCanHoldScreenshotsFromMoreThanOneAssessment() {
        // The reason notes cannot use the assessment-scoped indexing: a note is anchored to an
        // application, and its author moves between assessments.
        Assessment other = assessmentRepository.save(Assessment.builder()
                .name("Q2").applicationId(application.getId()).organizationId("org-1")
                .assessmentTypeId("t").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build());
        String fromFirst = uploadImage(assessment.getId());
        String fromSecond = uploadImage(other.getId());

        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle("Both");
        request.setContent(tag(fromFirst) + tag(fromSecond));
        notebookService.createNode(application.getId(), request, "tester");

        assertThat(inlineImageService.hasRefs(fromFirst)).isTrue();
        assertThat(inlineImageService.hasRefs(fromSecond)).isTrue();
    }

    @Test
    void deletingANoteReleasesItsScreenshots() {
        String imageId = uploadImage(assessment.getId());

        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle("Recon");
        request.setContent(tag(imageId));
        String nodeId = notebookService.createNode(application.getId(), request, "tester").getId();

        notebookService.deleteNode(nodeId, "tester");

        assertThat(inlineImageService.hasRefs(imageId)).isFalse();
    }

    @Test
    void twoNotesDoNotEvictEachOthersScreenshots() {
        String first = uploadImage(assessment.getId());
        String second = uploadImage(assessment.getId());

        CreateNotebookNodeRequest a = new CreateNotebookNodeRequest();
        a.setTitle("One"); a.setContent(tag(first));
        notebookService.createNode(application.getId(), a, "tester");

        CreateNotebookNodeRequest b = new CreateNotebookNodeRequest();
        b.setTitle("Two"); b.setContent(tag(second));
        notebookService.createNode(application.getId(), b, "tester");

        assertThat(inlineImageService.hasRefs(first)).isTrue();
        assertThat(inlineImageService.hasRefs(second)).isTrue();
    }

    @Test
    void anAssessmentIndexesFieldValuesGivenAtCreation() {
        AssessmentType type = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Pen Test").description("d").createdAt(LocalDateTime.now()).build());
        UserDefinedField field = UserDefinedField.builder()
                .id("exec-summary").displayName("Executive Summary").variableName("execSummary")
                .fieldType(com.faction.clientportal.model.FieldType.RICH_TEXT).fieldScope(FieldScope.ASSESSMENT).displayOrder(0).build();
        ReportTemplate template = reportTemplateRepository.save(ReportTemplate.builder()
                .name("T").description("d").assessmentTypeId(type.getId()).version(1).active(true)
                .userDefinedFields(new java.util.ArrayList<>(java.util.List.of(field)))
                .createdAt(LocalDateTime.now()).build());

        String imageId = uploadImage(assessment.getId());

        assessmentService.createAssessment(CreateAssessmentRequest.builder()
                .name("With evidence")
                .applicationId(application.getId())
                .assessmentTypeId(type.getId())
                .reportTemplateId(template.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .initialFieldValues(java.util.Map.of("exec-summary", tag(imageId)))
                .build(), "tester");

        // Only updateAssessment used to index, so an assessment created with an image in a field
        // and never edited again lost it to the GC the next night.
        assertThat(inlineImageService.hasRefs(imageId)).isTrue();
    }

    @Test
    void aDanglingReferenceIsNotIndexed() {
        // An id unique to this test, so the assertion cannot be satisfied or broken by a
        // reference some other test left behind.
        String missingId = "missing" + java.util.UUID.randomUUID().toString().replace("-", "");

        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle("Broken");
        request.setContent("<img src=\"/api/v1/inline-images/" + missingId + "\">");

        notebookService.createNode(application.getId(), request, "tester");

        // Indexing an id with no image behind it would create a reference nothing can release.
        assertThat(inlineImageService.hasRefs(missingId)).isFalse();
    }
}
