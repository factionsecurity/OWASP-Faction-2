package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where to send a file's bytes, returned by the "prepare upload" step.
 *
 * <p>Replaces the old presigned-URL response: {@code uploadUrl} is now a path on
 * this application, not a signed object-storage URL, so the upload arrives on an
 * authenticated request and storage is never exposed to the browser.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadTargetResponse {
    /** Temporary ID used to stream the body and then to confirm the upload */
    private String fileId;
    /** Application-relative path the client PUTs the file body to */
    private String uploadUrl;
    /** The storage key (for reference / debugging) */
    private String storageKey;
}
