package com.faction.clientportal.scheduled;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ContentTemplate;
import com.faction.clientportal.model.ContentTemplateScope;
import com.faction.clientportal.model.NotebookNode;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.ContentTemplateRepository;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.repository.NotebookNodeRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.InlineImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content that already exists has no image references, so without a backfill the first nightly GC
 * after upgrading would delete whatever screenshots had survived — the upgrade would itself cost a
 * customer a day's evidence.
 */
@SpringBootTest
@ActiveProfiles("test")
class InlineImageBackfillRunnerTest extends TestContainersConfig {

    @Autowired private InlineImageBackfillRunner runner;
    @Autowired private InlineImageGcJob gcJob;
    @Autowired private InlineImageService inlineImageService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private NotebookNodeRepository notebookNodeRepository;
    @Autowired private ContentTemplateRepository contentTemplateRepository;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private InlineImageRefRepository inlineImageRefRepository;

    private Assessment assessment;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        notebookNodeRepository.deleteAll();
        contentTemplateRepository.deleteAll();
        inlineImageRefRepository.deleteAll();
        inlineImageRepository.deleteAll();
        assessmentRepository.deleteAll();

        assessment = assessmentRepository.save(Assessment.builder()
                .name("Existing").applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("t").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).build());
    }

    private String uploadImage() {
        return inlineImageService.uploadImage(
                assessment.getId(), "s.png", "image/png", new byte[]{1}, "tester").getId();
    }

    private String tag(String imageId) {
        return "<p>Evidence</p><img src=\"/api/v1/inline-images/" + imageId + "\">";
    }

    /** Writes straight to the repository, which is what pre-upgrade content looks like. */
    private void existingFinding(String html) {
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("Legacy finding").severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessment.getId()).order(0).status("Open")
                .description(html).comments(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    @Test
    void aFindingSavedBeforeTheUpgradeGetsItsImagesIndexed() {
        String imageId = uploadImage();
        existingFinding(tag(imageId));
        assertThat(inlineImageService.hasRefs(imageId)).isFalse();

        runner.backfill();

        assertThat(inlineImageService.hasRefs(imageId)).isTrue();
    }

    @Test
    void withoutTheBackfillTheNextGcRunWouldHaveTakenIt() {
        String imageId = uploadImage();
        existingFinding(tag(imageId));
        // Age it past the grace period, as an image uploaded before the upgrade would be.
        var image = inlineImageRepository.findById(imageId).orElseThrow();
        image.setUploadedAt(LocalDateTime.now().minusDays(2));
        inlineImageRepository.save(image);

        runner.backfill();
        gcJob.run();

        assertThat(inlineImageRepository.findById(imageId)).isPresent();
    }

    @Test
    void notesAndTemplatesAreBackfilledToo() {
        String noteImage = uploadImage();
        String templateImage = inlineImageService.uploadLibraryImage(
                "d.png", "image/png", new byte[]{2}, "author").getId();

        notebookNodeRepository.save(NotebookNode.builder()
                .applicationId("app-1").title("Recon").content(tag(noteImage))
                .contentText("Recon").orderIndex(0).depth(0)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now()).lastModifiedAt(LocalDateTime.now()).build());
        contentTemplateRepository.save(ContentTemplate.builder()
                .name("Boilerplate").description("d").scope(ContentTemplateScope.VULNERABILITY)
                .content(tag(templateImage)).enabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        runner.backfill();

        assertThat(inlineImageService.hasRefs(noteImage)).isTrue();
        assertThat(inlineImageService.hasRefs(templateImage)).isTrue();
    }

    @Test
    void runningItTwiceChangesNothing() {
        String imageId = uploadImage();
        existingFinding(tag(imageId));

        runner.backfill();
        long afterFirst = inlineImageRefRepository.count();
        runner.backfill();

        // It reconciles rather than accumulates, so it is safe on every boot.
        assertThat(inlineImageRefRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    void aDeletedFindingIsNotIndexed() {
        String imageId = uploadImage();
        existingFinding(tag(imageId));
        var vuln = vulnerabilityRepository.findAll().get(0);
        vuln.setDeletedAt(LocalDateTime.now());
        vulnerabilityRepository.save(vuln);

        runner.backfill();

        // Its images should age out like any other orphan, not be held open forever.
        assertThat(inlineImageService.hasRefs(imageId)).isFalse();
    }

    @Test
    void contentWithNoImagesIsSkippedEntirely() {
        existingFinding("<p>Nothing but words.</p>");

        runner.backfill();

        assertThat(inlineImageRefRepository.count()).isZero();
    }
}
