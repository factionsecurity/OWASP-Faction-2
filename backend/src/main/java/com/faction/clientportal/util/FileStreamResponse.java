package com.faction.clientportal.util;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.Set;

/**
 * Builds the HTTP response for a file streamed out of object storage.
 *
 * <p><strong>Why the hardening headers matter here.</strong> These files are
 * user-uploaded and are now served from the application's own origin rather than
 * from a separate storage host. That means an uploaded HTML or SVG file rendered
 * in the browser would execute with access to the app's origin — stored XSS
 * against a tool that holds pentest reports. Three rules prevent it:
 *
 * <ul>
 *   <li>{@code Content-Disposition: attachment} on downloads, so the browser
 *       saves the file instead of rendering it;
 *   <li>an allowlist on inline-rendered images, so anything that is not a known
 *       raster image type is forced to download (SVG is excluded deliberately —
 *       it is an active-content format that can carry script);
 *   <li>{@code nosniff} plus a locked-down CSP on every response, so a mislabeled
 *       content type cannot be re-interpreted into something executable.
 * </ul>
 */
public final class FileStreamResponse {

    /**
     * Image types safe to render inline. SVG is excluded on purpose: it can carry
     * script and would execute in this origin. Anything absent here downloads.
     */
    private static final Set<String> INLINE_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/avif");

    private FileStreamResponse() {
    }

    /**
     * Stream a file as a download — always {@code attachment}, never rendered.
     *
     * @param object   Open stream from {@code StorageService.openStream}
     * @param fileName Name suggested to the browser's save dialog
     */
    public static ResponseEntity<Resource> attachment(
            ResponseInputStream<GetObjectResponse> object, String fileName) {
        return build(object, fileName, false);
    }

    /**
     * Stream an image for inline rendering in an {@code <img>} tag, falling back
     * to a download for any type not on the inline allowlist.
     *
     * @param object   Open stream from {@code StorageService.openStream}
     * @param fileName Name suggested if the browser downloads it instead
     */
    public static ResponseEntity<Resource> inlineImage(
            ResponseInputStream<GetObjectResponse> object, String fileName) {
        String contentType = object.response().contentType();
        boolean inline = contentType != null
                && INLINE_IMAGE_TYPES.contains(contentType.toLowerCase().trim());
        return build(object, fileName, inline);
    }

    private static ResponseEntity<Resource> build(
            ResponseInputStream<GetObjectResponse> object, String fileName, boolean inline) {

        GetObjectResponse metadata = object.response();

        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                // Emits both filename and filename* so non-ASCII names survive.
                .filename(fileName == null || fileName.isBlank() ? "download" : fileName,
                        java.nio.charset.StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.set(HttpHeaders.CONTENT_TYPE, resolveContentType(metadata, inline));
        headers.set("X-Content-Type-Options", "nosniff");
        // Belt and braces against the stored-XSS path described above: even if a
        // file is somehow rendered, it may not load or execute anything.
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        // These are per-user authorized responses; a shared cache must never
        // serve one user's report to the next requester.
        headers.setCacheControl("private, no-store");

        if (metadata.contentLength() != null) {
            headers.setContentLength(metadata.contentLength());
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(object));
    }

    /**
     * Inline responses keep their real type so the browser can render them;
     * downloads are deliberately flattened to octet-stream so the browser has
     * nothing to act on.
     */
    private static String resolveContentType(GetObjectResponse metadata, boolean inline) {
        if (inline && metadata.contentType() != null && !metadata.contentType().isBlank()) {
            return metadata.contentType();
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
