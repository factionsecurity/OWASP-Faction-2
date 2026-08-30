package com.faction.clientportal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These files are user-uploaded and are served from the application's own
 * origin, so the rules that stop one from being rendered as active content are
 * the ones worth pinning down.
 */
class FileStreamResponseTest {

    private static ResponseEntity<Resource> inline(String contentType) {
        return FileStreamResponse.inlineImage(
                StoredObjects.of("bytes".getBytes(StandardCharsets.UTF_8), contentType), "f.png");
    }

    @Test
    @DisplayName("downloads are always attachments, never rendered")
    void downloadsAreAttachments() {
        var response = FileStreamResponse.attachment(StoredObjects.of("data"), "report.docx");

        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("report.docx");
    }

    @Test
    @DisplayName("a download's content type is flattened so the browser cannot act on it")
    void downloadsAreOctetStream() {
        var response = FileStreamResponse.attachment(
                StoredObjects.of("<script>".getBytes(StandardCharsets.UTF_8), "text/html"), "evil.html");

        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("known raster image types render inline")
    void rasterImagesRenderInline() {
        for (String type : new String[]{"image/png", "image/jpeg", "image/gif", "image/webp"}) {
            var response = inline(type);
            assertThat(response.getHeaders().getContentDisposition().isInline())
                    .as("%s should render inline", type).isTrue();
            assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("SVG is never rendered inline — it can carry script")
    void svgIsForcedToDownload() {
        var response = inline("image/svg+xml");

        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("HTML masquerading as an image is forced to download")
    void htmlIsForcedToDownload() {
        assertThat(inline("text/html").getHeaders().getContentDisposition().isAttachment()).isTrue();
    }

    @Test
    @DisplayName("every response carries nosniff, a locked-down CSP, and no-store")
    void hardeningHeadersAlwaysPresent() {
        for (var response : new ResponseEntity[]{
                FileStreamResponse.attachment(StoredObjects.of("d"), "a.bin"), inline("image/png")}) {
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                    .isEqualTo("default-src 'none'; sandbox");
            // A shared cache must never hand one user's report to the next caller.
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        }
    }

    @Test
    @DisplayName("a blank filename falls back rather than emitting an empty disposition")
    void blankFilenameFallsBack() {
        var response = FileStreamResponse.attachment(StoredObjects.of("d"), "  ");

        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("download");
    }
}
