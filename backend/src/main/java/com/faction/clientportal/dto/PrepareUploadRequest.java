package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Asks the backend to allocate a file id and upload target before the body is
 * streamed. Same shape as the presign request it replaces, so clients only need
 * to change where they send the body — not how they announce it.
 */
@Data
public class PrepareUploadRequest {
    @NotBlank
    private String fileName;
    @NotBlank
    private String contentType;
    @Positive
    private Long fileSize;
}
