package com.faction.clientportal.scheduled;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import com.faction.clientportal.service.InlineImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job that deletes unreferenced inline images had no test of its own, and it is the one that
 * caused the data loss this branch fixes: it was working exactly as designed, deleting images that
 * genuinely had no references, because nothing was writing references for findings or notes.
 *
 * <p>So these pin both halves. What it must delete, or storage grows without bound; and what it
 * must not, which is the half that failed in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class InlineImageGcJobTest extends TestContainersConfig {

    @Autowired private InlineImageGcJob job;
    @Autowired private InlineImageService inlineImageService;
    @Autowired private InlineImageRepository inlineImageRepository;
    @Autowired private InlineImageRefRepository inlineImageRefRepository;

    @BeforeEach
    void setUp() {
        inlineImageRefRepository.deleteAll();
        inlineImageRepository.deleteAll();
    }

    /** An image uploaded long enough ago to be past the job's 24-hour grace period. */
    private String oldImage() {
        String id = inlineImageService.uploadImage(
                "assessment-1", "shot.png", "image/png", new byte[]{1, 2, 3}, "tester").getId();
        InlineImage image = inlineImageRepository.findById(id).orElseThrow();
        image.setUploadedAt(LocalDateTime.now().minusDays(3));
        inlineImageRepository.save(image);
        return id;
    }

    @Test
    void anOldImageNothingReferencesIsDeleted() {
        String abandoned = oldImage();

        job.run();

        // Someone pasted an image and abandoned the edit. This is what the job is for.
        assertThat(inlineImageRepository.findById(abandoned)).isEmpty();
    }

    @Test
    void anOldImageSomethingReferencesIsKept() {
        String inUse = oldImage();
        inlineImageService.updateRefsForField("assessment-1", "vulnerability/v1/description",
                "<img src=\"/api/v1/inline-images/" + inUse + "\">");

        job.run();

        // The half that was broken: a referenced image must survive, however old it is.
        assertThat(inlineImageRepository.findById(inUse)).isPresent();
    }

    @Test
    void aRecentImageIsSparedEvenWithNoReferences() {
        // The grace period exists because an image is uploaded before the field holding it is
        // saved — without it, pasting a screenshot and pausing for thought would lose it.
        String justUploaded = inlineImageService.uploadImage(
                "assessment-1", "fresh.png", "image/png", new byte[]{4}, "tester").getId();

        job.run();

        assertThat(inlineImageRepository.findById(justUploaded)).isPresent();
    }

    @Test
    void aLibraryImageHeldByATemplateIsKept() {
        String libraryId = inlineImageService.uploadLibraryImage(
                "diagram.png", "image/png", new byte[]{7}, "author").getId();
        InlineImage image = inlineImageRepository.findById(libraryId).orElseThrow();
        image.setUploadedAt(LocalDateTime.now().minusDays(3));
        inlineImageRepository.save(image);
        inlineImageService.updateRefsForSharedField("content-template/t1/content",
                "<img src=\"/api/v1/inline-images/" + libraryId + "\">");

        job.run();

        // A library image belongs to no assessment, so it is only ever held by a template's
        // reference — nothing else would keep it alive.
        assertThat(inlineImageRepository.findById(libraryId)).isPresent();
    }

    @Test
    void releasingTheLastReferenceMakesAnImageCollectableAgain() {
        String imageId = oldImage();
        inlineImageService.updateRefsForField("assessment-1", "vulnerability/v1/description",
                "<img src=\"/api/v1/inline-images/" + imageId + "\">");
        job.run();
        assertThat(inlineImageRepository.findById(imageId)).isPresent();

        // The image is edited out of the field.
        inlineImageService.updateRefsForField("assessment-1", "vulnerability/v1/description", "");
        job.run();

        assertThat(inlineImageRepository.findById(imageId)).isEmpty();
    }
}
