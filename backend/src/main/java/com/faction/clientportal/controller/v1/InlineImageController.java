package com.faction.clientportal.controller.v1;

import com.faction.clientportal.model.InlineImageScope;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.InlineImageUploadResponse;
import com.faction.clientportal.service.AccessScopeService;
import com.faction.clientportal.service.InlineImageService;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.FileStreamResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
@Tag(name = "Inline Images", description = "Upload and serve inline images embedded in rich-text fields")
public class InlineImageController {

    private final InlineImageService inlineImageService;
    private final AccessScopeService accessScopeService;

    /**
     * Upload an inline image for an assessment's rich-text field.
     * Returns a short link URL to embed in the editor.
     */
    @PostMapping("/api/v1/assessments/{assessmentId}/inline-images")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_ASSIGNED})
    @Operation(summary = "Upload an inline image",
               description = "Uploads an image for an assessment's rich-text field and returns a short link URL to embed in the editor.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<?> upload(
            @PathVariable String assessmentId,
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "image";
            String contentType = file.getContentType() != null
                    ? file.getContentType() : "application/octet-stream";

            InlineImageUploadResponse result = inlineImageService.uploadImage(
                    assessmentId, filename, contentType, bytes, auth.getName());

            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "Failed to read uploaded file"));
        }
    }

    /**
     * Upload an image owned by a template rather than by an assessment.
     *
     * <p>Gated on authoring a template, because that is what the image is for. Anyone who can
     * write boilerplate that every assessment reuses can add a picture to it.
     */
    @PostMapping("/api/v1/inline-images/library")
    @RequiresPermission({Permission.CONTENT_TEMPLATES_CREATE, Permission.CONTENT_TEMPLATES_EDIT,
            Permission.DEFAULT_VULNERABILITIES_CREATE, Permission.DEFAULT_VULNERABILITIES_EDIT})
    @Operation(summary = "Upload a reusable template image",
               description = "Uploads an image for a content template or default vulnerability. "
                       + "Unlike an assessment's images it is not scoped to one engagement — it is "
                       + "readable by any authenticated user, and is copied into an assessment when "
                       + "the template is used there, so the assessment's copy cannot change "
                       + "underneath it afterwards.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<?> uploadLibrary(
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        try {
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image";
            String contentType = file.getContentType() != null
                    ? file.getContentType() : "application/octet-stream";

            InlineImageUploadResponse result = inlineImageService.uploadLibraryImage(
                    filename, contentType, file.getBytes(), auth.getName());

            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "Failed to read uploaded file"));
        }
    }

    /**
     * Stream an inline image to the browser.
     *
     * <p>Authenticated, and scoped to the assessment the image belongs to: these
     * are screenshots of findings, so an unguessable id is not sufficient
     * protection on its own. The browser requests this from an {@code <img>} tag
     * and so cannot send an Authorization header — it presents the media cookie
     * instead, which this path is on the allowlist for.
     */
    @GetMapping("/api/v1/inline-images/{imageId}")
    @Operation(summary = "Serve an inline image",
               description = "Streams the image bytes. Requires an authenticated caller with read "
                       + "access to the owning assessment. Returns 404 if the image does not exist.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Resource> serve(@PathVariable String imageId, Authentication authentication) {
        try {
            // A library image belongs to a template, not an engagement, so there is no assessment
            // to scope it to — and it has to load for whoever is reading the finding it ended up
            // in, which includes app owners. Templates are boilerplate, so that is acceptable;
            // client evidence is never LIBRARY scope.
            if (inlineImageService.getScope(imageId) != InlineImageScope.LIBRARY) {
                accessScopeService.checkAssessmentAccess(
                        authentication, inlineImageService.getAssessmentId(imageId));
            }
            StorageService.StoredFile file = inlineImageService.openImage(imageId);
            return FileStreamResponse.inlineImage(file.stream(), file.fileName());
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
