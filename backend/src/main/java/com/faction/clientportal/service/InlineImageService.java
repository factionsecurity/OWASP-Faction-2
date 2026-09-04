package com.faction.clientportal.service;

import com.faction.clientportal.dto.InlineImageUploadResponse;
import com.faction.clientportal.model.InlineImage;
import com.faction.clientportal.model.InlineImageRef;
import com.faction.clientportal.model.InlineImageScope;
import com.faction.clientportal.repository.InlineImageRefRepository;
import com.faction.clientportal.repository.InlineImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** An inline image's bytes and media type, ready to be embedded in an export. */
    public record EmbeddedImage(byte[] content, String contentType) {}

    /**
     * Reads an inline image into memory for embedding in an export document.
     *
     * <p>Empty rather than throwing when the image is unknown, too large, or unreadable: an export
     * carrying most of its screenshots beats one that fails outright because a single object is
     * missing from storage. The size is checked from the tracking row before any bytes are pulled,
     * so an oversized image costs nothing.
     *
     * @param maxBytes refuse anything larger; the caller is budgeting a whole document
     */
    public Optional<EmbeddedImage> loadForEmbedding(String imageId, long maxBytes) {
        InlineImage image = inlineImageRepository.findById(imageId).orElse(null);
        if (image == null) {
            return Optional.empty();
        }
        if (image.getFileSize() != null && image.getFileSize() > maxBytes) {
            log.debug("Inline image {} is {} bytes, over the {} budget — not embedded",
                    imageId, image.getFileSize(), maxBytes);
            return Optional.empty();
        }
        try (var stream = storageService.openStream(image.getStorageKey())) {
            byte[] content = stream.readAllBytes();
            if (content.length > maxBytes) {
                return Optional.empty();
            }
            String contentType = image.getContentType() == null || image.getContentType().isBlank()
                    ? "application/octet-stream"
                    : image.getContentType();
            return Optional.of(new EmbeddedImage(content, contentType));
        } catch (Exception e) {
            log.warn("Could not read inline image {} for export: {}", imageId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Uploads an image owned by a template rather than by an assessment.
     *
     * <p>Reusable across assessments, and readable by any authenticated user — see
     * {@link InlineImageScope#LIBRARY} for why that is unavoidable and what it costs.
     */
    public InlineImageUploadResponse uploadLibraryImage(
            String filename, String contentType, byte[] bytes, String uploadedBy) {
        String imageId = UUID.randomUUID().toString().replace("-", "");
        String storageKey = String.format("inline-images/library/%s/%s", imageId, filename);

        storageService.uploadBytes(storageKey, bytes, contentType);

        inlineImageRepository.save(InlineImage.builder()
                .id(imageId)
                .assessmentId(null)
                .scope(InlineImageScope.LIBRARY)
                .storageKey(storageKey)
                .originalFileName(filename)
                .contentType(contentType)
                .fileSize((long) bytes.length)
                .uploadedBy(uploadedBy)
                .uploadedAt(LocalDateTime.now())
                .build());
        log.info("Uploaded library inline image {}", imageId);

        return new InlineImageUploadResponse(imageId, "/api/v1/inline-images/" + imageId);
    }

    /** The image's scope, for the access check on serving it. */
    public InlineImageScope getScope(String imageId) {
        return inlineImageRepository.findById(imageId)
                .map(i -> i.getScope() == null ? InlineImageScope.ASSESSMENT : i.getScope())
                .orElseThrow(() -> new NoSuchElementException("Inline image not found: " + imageId));
    }

    /**
     * The image id an assessment's content should point at.
     *
     * <p>Already owned by that assessment, and it is left alone. Owned by anything else — a
     * template's library image, or another assessment's — and it is copied in, because an inline
     * image authorises against its owner: content referencing someone else's image renders broken
     * for exactly the readers it was written for. Copying at the point of use is also what keeps a
     * finalized report fixed, rather than depending on a shared object someone can later change.
     *
     * @return the id to reference, unchanged when no copy was needed
     */
    public String materializeInto(String imageId, String targetAssessmentId, String userId) {
        InlineImage image = inlineImageRepository.findById(imageId).orElse(null);
        if (image == null) {
            // A dangling reference. Left as it is: a broken image someone can investigate beats
            // failing the save it happens to be sitting in.
            return imageId;
        }
        if (targetAssessmentId.equals(image.getAssessmentId())) {
            return imageId;
        }
        return copyToAssessment(imageId, targetAssessmentId, userId).orElse(imageId);
    }

    /**
     * Copies an image into another assessment, as a new image owned by that assessment.
     *
     * <p>Needed because an inline image is authorised against its owning assessment: HTML moved
     * between assessments has to point at an image the new assessment owns, or it 403s for anyone
     * who cannot read the original. A real copy rather than a second row over the same storage
     * key — {@link #deleteImage} removes the object, so sharing a key would let the GC blank one
     * finding's screenshot by reaping another's.
     *
     * @return the new image's id, or empty when the source could not be read
     */
    public Optional<String> copyToAssessment(String sourceImageId, String targetAssessmentId, String userId) {
        InlineImage source = inlineImageRepository.findById(sourceImageId).orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        byte[] bytes;
        try (var stream = storageService.openStream(source.getStorageKey())) {
            bytes = stream.readAllBytes();
        } catch (Exception e) {
            log.warn("Could not read inline image {} to copy it: {}", sourceImageId, e.getMessage());
            return Optional.empty();
        }
        String filename = source.getOriginalFileName() == null ? "image" : source.getOriginalFileName();
        String contentType = source.getContentType() == null
                ? "application/octet-stream" : source.getContentType();
        // Always lands as ASSESSMENT scope, whatever the source was: a copy taken into an
        // assessment is that assessment's evidence now, not shared boilerplate.
        return Optional.of(uploadImage(targetAssessmentId, filename, contentType, bytes, userId).getId());
    }

    /** Whether the tracking row exists — tells a dangling reference from one refused for size. */
    public boolean exists(String imageId) {
        return inlineImageRepository.existsById(imageId);
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
    @Transactional
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
     * Reference index for a field that is not scoped to one assessment.
     *
     * <p>{@link #updateRefsForField} keys on (assessment, field) and assumes every image in the
     * content belongs to that assessment. A notebook node breaks that assumption: it is anchored
     * to an application, only root nodes carry an assessment id at all, and its screenshots are
     * uploaded against whichever assessment the author happened to be viewing. So each reference
     * is filed under the assessment that owns <em>that image</em> — which is also what makes
     * {@link #deleteRefsForAssessment} release it at the right time — and reconciliation is by
     * field alone.
     *
     * @param fieldId globally unique for the field, since it is the whole reconciliation key
     */
    @Transactional
    public void updateRefsForSharedField(String fieldId, String content) {
        Set<String> currentIds = extractImageIds(content);
        List<InlineImageRef> existing = inlineImageRefRepository.findByFieldId(fieldId);
        Set<String> existingIds = existing.stream()
                .map(InlineImageRef::getImageId)
                .collect(Collectors.toSet());

        for (String imageId : currentIds) {
            if (existingIds.contains(imageId)) continue;
            // An id in the content with no image behind it is a dangling reference, not something
            // to index — indexing it would create a ref that nothing can ever release.
            inlineImageRepository.findById(imageId).ifPresent(image ->
                    inlineImageRefRepository.save(InlineImageRef.builder()
                            .imageId(imageId)
                            .assessmentId(image.getAssessmentId())
                            .fieldId(fieldId)
                            .updatedAt(LocalDateTime.now())
                            .build()));
        }

        for (InlineImageRef ref : existing) {
            if (!currentIds.contains(ref.getImageId())) {
                inlineImageRefRepository.delete(ref);
            }
        }
    }

    /**
     * Remove all reference records for an assessment (called on assessment delete).
     *
     * <p>{@code @Transactional} because a derived {@code deleteBy…} loads the rows and removes
     * them one by one, and {@code remove} needs a transaction. Nothing on the delete path supplied
     * one, so deleting an assessment that had any inline image reference threw
     * {@code TransactionRequiredException} — an assessment with no images deleted fine, which is
     * why it went unnoticed.
     */
    @Transactional
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
