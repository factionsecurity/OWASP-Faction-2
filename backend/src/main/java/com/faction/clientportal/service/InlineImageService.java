package com.faction.clientportal.service;

import com.faction.clientportal.dto.InlineImageUploadResponse;
import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.model.InlineImageRef;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InlineImageService {

    private static final Pattern IMAGE_ID_PATTERN =
            Pattern.compile("/api/v1/inline-images/([a-zA-Z0-9]+)");

    private final InlineImageRepository inlineImageRepository;
    private final InlineImageRefRepository inlineImageRefRepository;
    private final StorageService storageService;

    /**
     * Upload an inline image for an assessment. Stores to MinIO and creates a tracking record.
     *
     * @return Short-link URL to embed in the editor
     */
    public InlineImageUploadResponse uploadImage(
            String assessmentId,
            String filename,
            String contentType,
            byte[] bytes,
            String uploadedBy
    ) {
        String imageId = UUID.randomUUID().toString().replace("-", "");
        String storageKey = String.format("inline-images/%s/%s/%s", assessmentId, imageId, filename);

        storageService.uploadBytes(storageKey, bytes, contentType);

        InlineImage image = InlineImage.builder()
                .id(imageId)
                .assessmentId(assessmentId)
                .storageKey(storageKey)
                .originalFileName(filename)
                .contentType(contentType)
                .fileSize((long) bytes.length)
                .uploadedBy(uploadedBy)
                .uploadedAt(LocalDateTime.now())
                .build();

        // Use explicit ID so our generated UUID is preserved
        inlineImageRepository.save(image);
        log.info("Uploaded inline image {} for assessment {}", imageId, assessmentId);

        return new InlineImageUploadResponse(imageId, "/api/v1/inline-images/" + imageId);
    }

    /**
     * Open an inline image's bytes for streaming to the client. The caller owns
     * the returned stream and must close it.
     */
    public StorageService.StoredFile openImage(String imageId) {
        InlineImage image = inlineImageRepository.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Inline image not found: " + imageId));
        return new StorageService.StoredFile(
                storageService.openStream(image.getStorageKey()), image.getOriginalFileName());
    }

    /** The assessment an inline image belongs to, for access-scope checks. */
    public String getAssessmentId(String imageId) {
        return inlineImageRepository.findById(imageId)
                .map(InlineImage::getAssessmentId)
                .orElseThrow(() -> new NoSuchElementException("Inline image not found: " + imageId));
    }

    /**
     * Update the reference index for a single rich-text field after it is saved.
     * Extracts all inline image IDs from the content, upserts refs, and removes stale ones.
     */
    public void updateRefsForField(String assessmentId, String fieldId, String content) {
        Set<String> currentIds = extractImageIds(content);

        // Fetch existing refs for this (assessment, field)
        List<InlineImageRef> existing = inlineImageRefRepository
                .findByAssessmentIdAndFieldId(assessmentId, fieldId);
        Set<String> existingIds = existing.stream()
                .map(InlineImageRef::getImageId)
                .collect(Collectors.toSet());

        // Add new refs
        for (String imageId : currentIds) {
            if (!existingIds.contains(imageId)) {
                inlineImageRefRepository.save(InlineImageRef.builder()
                        .imageId(imageId)
                        .assessmentId(assessmentId)
                        .fieldId(fieldId)
                        .updatedAt(LocalDateTime.now())
                        .build());
            }
        }

        // Remove stale refs (image was removed from this field)
        for (InlineImageRef ref : existing) {
            if (!currentIds.contains(ref.getImageId())) {
                inlineImageRefRepository.delete(ref);
            }
        }
    }

    /**
     * Remove all reference records for an assessment (called on assessment delete).
     */
    public void deleteRefsForAssessment(String assessmentId) {
        inlineImageRefRepository.deleteByAssessmentId(assessmentId);
        log.info("Deleted inline image refs for assessment {}", assessmentId);
    }

    /**
     * Delete a specific InlineImage and its storage object (called by GC).
     */
    public void deleteImage(InlineImage image) {
        try {
            storageService.deleteObject(image.getStorageKey());
        } catch (Exception e) {
            log.warn("Could not delete storage object {}: {}", image.getStorageKey(), e.getMessage());
        }
        inlineImageRepository.delete(image);
        log.info("Deleted orphaned inline image {}", image.getId());
    }

    /**
     * Return all InlineImage records uploaded before the given threshold.
     */
    public List<InlineImage> findCandidatesForGc(LocalDateTime uploadedBefore) {
        return inlineImageRepository.findByUploadedAtBefore(uploadedBefore);
    }

    /**
     * True if at least one ref exists for this image (it is still used somewhere).
     */
    public boolean hasRefs(String imageId) {
        return inlineImageRefRepository.countByImageId(imageId) > 0;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Set<String> extractImageIds(String content) {
        if (content == null || content.isBlank()) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        Matcher m = IMAGE_ID_PATTERN.matcher(content);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }
}
