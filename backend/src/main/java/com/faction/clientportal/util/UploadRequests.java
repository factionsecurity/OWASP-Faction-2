package com.faction.clientportal.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

/**
 * Validates the framing of a raw streaming upload before its body is piped into
 * storage.
 *
 * <p>Both checks exist because the body is never buffered: nothing downstream
 * counts the bytes for us, so an unbounded or unmeasured stream would either be
 * rejected deep inside the storage SDK or write until the disk filled.
 */
@Component
public class UploadRequests {

    /**
     * Ceiling on a single uploaded file. Keep this at or below nginx's
     * {@code client_max_body_size}, otherwise the proxy rejects the request first
     * and the caller sees nginx's HTML error page instead of a JSON error.
     */
    @Value("${storage.max-upload-bytes:524288000}")
    private long maxUploadBytes;

    /**
     * Exact body length, which the storage SDK needs up front in order to sign
     * the request.
     *
     * <p>A chunked request has no declared length and is rejected rather than
     * buffered — buffering to discover the size is exactly the heap blow-up this
     * streaming path exists to avoid. Browsers set Content-Length automatically
     * when the body is a File or Blob, so this only trips on hand-rolled clients.
     */
    public long contentLength(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "A Content-Length header is required; chunked uploads are not supported");
        }
        if (length > maxUploadBytes) {
            throw new ResponseStatusException(PAYLOAD_TOO_LARGE,
                    "File exceeds the maximum upload size of " + maxUploadBytes + " bytes");
        }
        return length;
    }

    /** Declared content type, falling back to octet-stream when absent. */
    public String contentType(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;
    }
}
