package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateVulnerabilityRequest;
import com.faction.clientportal.dto.SaveContentTemplateRequest;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ContentTemplateScope;
import com.faction.clientportal.model.InlineImageScope;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ContentTemplateRepository;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Template images: uploaded once, reusable across assessments, and copied into an assessment at
 * the point of use rather than shared with it.
 *
 * <p>Copying at use is what keeps a finalized report fixed. If a finding pointed at the shared
 * library image, editing the template years later would silently rewrite the evidence in an
 * assessment that had already been signed off.
 */
@SpringBootTest
@ActiveProfiles("test")
class LibraryImageTest extends TestContainersConfig {

    @Autowired private InlineImageService inlineImageService;
    @Autowired private VulnerabilityService vulnerabilityService;
    @Autowired private ContentTemplateService contentTemplateService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private ContentTemplateRepository contentTemplateRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private InlineImageRefRepository inlineImageRefRepository;

    private static final Pattern IMAGE_REF = Pattern.compile("/api/v1/inline-images/([a-zA-Z0-9]+)");

    private Assessment first;
    private Assessment second;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        contentTemplateRepository.deleteAll();
        inlineImageRefRepository.deleteAll();
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();

        first = assessmentRepository.save(assessment("First"));
        second = assessmentRepository.save(assessment("Second"));
    }

    private String uploadLibraryImage() {
        return inlineImageService.uploadLibraryImage(
                "diagram.png", "image/png", new byte[]{9, 9, 9}, "author").getId();
    }

    private String tag(String imageId) {
        return "<p>See the diagram</p><img src=\"/api/v1/inline-images/" + imageId + "\">";
    }

    private String findingWithImage(Assessment target, String imageId) {
        CreateVulnerabilityRequest request = new CreateVulnerabilityRequest();
        request.setName("From template");
        request.setSeverity(VulnerabilitySeverity.HIGH);
        request.setDescription(tag(imageId));
        String id = vulnerabilityService.create(target.getId(), request, "tester").getId();
        return firstImageId(vulnerabilityRepository.findById(id).orElseThrow().getDescription());
    }

    @Test
    void aLibraryImageIsNotOwnedByAnyAssessment() {
        String imageId = uploadLibraryImage();

        assertThat(inlineImageService.getScope(imageId)).isEqualTo(InlineImageScope.LIBRARY);
        assertThat(inlineImageRepository.findById(imageId).orElseThrow().getAssessmentId()).isNull();
    }

    @Test
    void usingATemplateImageCopiesItIntoTheAssessment() {
        String libraryId = uploadLibraryImage();

        String usedId = findingWithImage(first, libraryId);

        assertThat(usedId).isNotEqualTo(libraryId);
        assertThat(inlineImageService.getAssessmentId(usedId)).isEqualTo(first.getId());
        // The copy is that assessment's own evidence now, not shared boilerplate.
        assertThat(inlineImageService.getScope(usedId)).isEqualTo(InlineImageScope.ASSESSMENT);
        // …and the library original is untouched, ready for the next assessment.
        assertThat(inlineImageService.getScope(libraryId)).isEqualTo(InlineImageScope.LIBRARY);
    }

    @Test
    void theSameTemplateImageCanBeUsedByTwoAssessmentsIndependently() {
        String libraryId = uploadLibraryImage();

        String inFirst = findingWithImage(first, libraryId);
        String inSecond = findingWithImage(second, libraryId);

        assertThat(inFirst).isNotEqualTo(inSecond);
        assertThat(inlineImageService.getAssessmentId(inFirst)).isEqualTo(first.getId());
        assertThat(inlineImageService.getAssessmentId(inSecond)).isEqualTo(second.getId());
    }

    @Test
    void resavingAFindingDoesNotKeepCopyingTheImage() {
        String libraryId = uploadLibraryImage();
        String usedId = findingWithImage(first, libraryId);
        long afterFirstSave = inlineImageRepository.count();

        // The image is already owned by this assessment, so an edit leaves it alone. Without that
        // check every save would mint another copy.
        var vuln = vulnerabilityRepository.findAll().stream()
                .filter(v -> first.getId().equals(v.getAssessmentId())).findFirst().orElseThrow();
        com.faction.clientportal.dto.UpdateVulnerabilityRequest update =
                new com.faction.clientportal.dto.UpdateVulnerabilityRequest();
        update.setDescription(vuln.getDescription());
        vulnerabilityService.update(first.getId(), vuln.getId(), update, "tester");

        assertThat(inlineImageRepository.count()).isEqualTo(afterFirstSave);
        assertThat(firstImageId(vulnerabilityRepository.findById(vuln.getId()).orElseThrow()
                .getDescription())).isEqualTo(usedId);
    }

    @Test
    void aContentTemplateHoldsItsLibraryImagesOpen() {
        String libraryId = uploadLibraryImage();

        SaveContentTemplateRequest request = new SaveContentTemplateRequest();
        request.setName("Boilerplate");
        request.setScope(ContentTemplateScope.VULNERABILITY);
        request.setContent(tag(libraryId));
        contentTemplateService.createTemplate(request, "author");

        // Otherwise the GC deletes it the night after upload, like every other unindexed image.
        assertThat(inlineImageService.hasRefs(libraryId)).isTrue();
    }

    @Test
    void deletingAnAssessmentThatHasImagesDoesNotBlowUp() {
        // Regression: deleteRefsForAssessment removes rows one at a time, which needs a
        // transaction, and nothing on the delete path supplied one. An assessment with no images
        // deleted fine, which is why this went unnoticed.
        String imageId = inlineImageService.uploadImage(
                first.getId(), "s.png", "image/png", new byte[]{1}, "tester").getId();
        inlineImageService.updateRefsForField(first.getId(), "exec-summary", tag(imageId));

        inlineImageService.deleteRefsForAssessment(first.getId());

        assertThat(inlineImageService.hasRefs(imageId)).isFalse();
    }

    @Test
    void aTemplateImageSurvivesTheAssessmentThatUsedItBeingDeleted() {
        String libraryId = uploadLibraryImage();

        SaveContentTemplateRequest request = new SaveContentTemplateRequest();
        request.setName("Boilerplate");
        request.setScope(ContentTemplateScope.VULNERABILITY);
        request.setContent(tag(libraryId));
        contentTemplateService.createTemplate(request, "author");
        findingWithImage(first, libraryId);

        // deleteRefsForAssessment releases an assessment's images; the library original is held by
        // the template, so it must not be swept up alongside the copy.
        inlineImageService.deleteRefsForAssessment(first.getId());

        assertThat(inlineImageService.hasRefs(libraryId)).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String firstImageId(String html) {
        Matcher m = IMAGE_REF.matcher(html == null ? "" : html);
        assertThat(m.find()).as("expected an image reference in: %s", html).isTrue();
        return m.group(1);
    }

    private Assessment assessment(String name) {
        return Assessment.builder()
                .name(name).applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("t").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build();
    }
}
